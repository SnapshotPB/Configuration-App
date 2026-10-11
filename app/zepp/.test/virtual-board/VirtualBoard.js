// The virtual Snapshot PB board: an Object Transfer Service server that speaks
// the real OACP/OLCP handshake and moves real protobuf bytes.
//
// Opcodes and result codes are spelled out here from the OTS spec rather than
// imported from src/protocol/OtsOpcodes.js on purpose — if the app's table ever
// drifts from the spec, the board must disagree with it, not agree by
// construction. Protobuf encoding DOES come from the generated bindings (see
// boardState.js): the schema is shared with the KMP client, the opcodes are the
// thing under test.
//
// The board is transport-agnostic. VirtualRadio attaches itself and supplies
// scheduling, UUID formatting and delivery; anything implementing the same
// attach()/read/write/subscribe surface (a recorded-trace responder, say) can
// take its place.

import { TextEncoder as NodeTextEncoder } from 'node:util';
import { applyObjectWrite, createBoardState, encodeObject, OTS_OBJECTS, padObject } from './boardState';

// Object Transfer Service UUIDs, short form. The vendor ObjectData
// characteristic carries chunked content over GATT notifications — this
// firmware has no L2CAP CoC, matching the KMP OtsClient.
export const OTS_UUID = {
  SERVICE: '1825',
  FEATURE: '2ABD',
  OBJECT_NAME: '2ABE',
  OBJECT_TYPE: '2ABF',
  OBJECT_SIZE: '2AC0',
  OBJECT_ID: '2AC3',
  OBJECT_PROPERTIES: '2AC4',
  OACP: '2AC5',
  OLCP: '2AC6',
  DEVICE_OBJECT_TYPE: '00000001-5350-4221-0000-5350425F4F54',
  OBJECT_DATA: '00000002-5350-4221-0000-5350425F4F54'
};

// OTS spec §3.11.2 / §3.12.2.
const OACP_OP = { CREATE: 0x01, DELETE: 0x02, CHECKSUM: 0x03, EXECUTE: 0x04, READ: 0x05, WRITE: 0x06, ABORT: 0x07, RESPONSE: 0x60 };
const OLCP_OP = { FIRST: 0x01, LAST: 0x02, PREVIOUS: 0x03, NEXT: 0x04, GOTO: 0x05, ORDER: 0x06, NUMBER_OF_OBJECTS: 0x07, CLEAR_MARKING: 0x08, RESPONSE: 0x70 };

// OTS spec §3.11.3 (OACP) / §3.12.3 (OLCP) result codes.
const OACP_RESULT = { SUCCESS: 0x01, OPCODE_NOT_SUPPORTED: 0x02, INVALID_PARAMETER: 0x03, INSUFFICIENT_RESOURCES: 0x04, INVALID_OBJECT: 0x05, PROCEDURE_NOT_PERMITTED: 0x07, OPERATION_FAILED: 0x0a };
const OLCP_RESULT = { SUCCESS: 0x01, OPCODE_NOT_SUPPORTED: 0x02, INVALID_PARAMETER: 0x03, OPERATION_FAILED: 0x04, OUT_OF_BOUNDS: 0x05, NO_OBJECT: 0x07 };

export { OACP_OP, OLCP_OP, OACP_RESULT, OLCP_RESULT };

const CANONICAL_16_BIT = /^0000([0-9A-F]{4})-0000-1000-8000-00805F9B34FB$/;

/** Reduces a canonical 128-bit assigned UUID back to its 16-bit short form. */
export function normalizeUuid(uuid) {
  const upper = String(uuid || '').toUpperCase();
  const match = CANONICAL_16_BIT.exec(upper);
  return match ? match[1] : upper;
}

// The board side is test code, not app code. It takes its encoder from node:util because the
// harness deletes the global TextEncoder to match the watch runtime (see mocks/runtime.js).
const boardTextEncoder = new NodeTextEncoder();

function utf8(text) {
  return boardTextEncoder.encode(text);
}

function readU32(bytes, offset) {
  return (
    (bytes[offset] | (bytes[offset + 1] << 8) | (bytes[offset + 2] << 16) | (bytes[offset + 3] << 24)) >>> 0
  );
}

function writeU32(bytes, offset, value) {
  bytes[offset] = value & 0xff;
  bytes[offset + 1] = (value >> 8) & 0xff;
  bytes[offset + 2] = (value >> 16) & 0xff;
  bytes[offset + 3] = (value >> 24) & 0xff;
}

export class VirtualBoard {
  /**
   * @param {object} [config]
   * @param {object} [config.state]    board state fixture (see createBoardState)
   * @param {object} [config.faults]   fault injection, shared with VirtualRadio
   * @param {number} [config.chunkSize]      ObjectData notification size (default 20, one ATT MTU)
   * @param {number} [config.padAlignment]   flash slot alignment for object padding
   * @param {boolean} [config.strictSubscriptions] drop notifications for unsubscribed characteristics
   */
  constructor(config = {}) {
    this.state = config.state || createBoardState();
    this.faults = config.faults || {};
    this.chunkSize = config.chunkSize || 20;
    this.padAlignment = config.padAlignment === undefined ? 32 : config.padAlignment;
    this.strictSubscriptions = config.strictSubscriptions !== false;

    // Object list, in the order OLCP FIRST/NEXT walks it. Descriptors come from
    // the protocol table in boardState.js, so a new object needs no change here.
    this.objects = config.objects || OTS_OBJECTS;

    this.transport = null;
    this.resetSession();

    // Observability for assertions.
    this.droppedNotifications = [];
    this.appliedWrites = [];
  }

  attach(transport) {
    this.transport = transport;
  }

  /** Everything a GATT link owns. Called on connect and on disconnect. */
  resetSession() {
    this.selectedIndex = -1;
    this.subscriptions = new Map();
    this.pendingWrite = null;
  }

  // --- Object accessors -----------------------------------------------------

  /** The descriptor of the currently selected object, or null. */
  selected() {
    return this.selectedIndex >= 0 ? this.objects[this.selectedIndex] : null;
  }

  selectedName() {
    const descriptor = this.selected();
    return descriptor ? descriptor.name : null;
  }

  /** The padded on-the-wire bytes of a named object. */
  objectBytes(name) {
    const descriptor = this.objects.find(o => o.name === name);
    if (!descriptor) return null;
    return padObject(encodeObject(this.state, descriptor), this.padAlignment);
  }

  // --- GATT surface ---------------------------------------------------------

  /**
   * @returns {{value: Uint8Array}|{error: string}} the value is delivered by the
   * radio through mstOnCharaValueArrived, an error through the read's fail().
   */
  readCharacteristic(uuid) {
    const short = normalizeUuid(uuid);
    if (this.faults.readError && normalizeUuid(this.faults.readError) === short) {
      return { error: `read of ${short} rejected by fault injection` };
    }

    switch (short) {
      case OTS_UUID.FEATURE: {
        // OACP features + OLCP features, 4 bytes each.
        const value = new Uint8Array(8);
        writeU32(value, 0, (1 << 2) | (1 << 3)); // read + write
        writeU32(value, 4, 1 << 0); // go-to
        return { value };
      }
      case OTS_UUID.OBJECT_NAME: {
        const name = this.selectedName();
        if (name === null) return { error: 'no object selected' };
        return { value: utf8(name) };
      }
      case OTS_UUID.OBJECT_TYPE: {
        if (this.selectedIndex < 0) return { error: 'no object selected' };
        return { value: utf8(OTS_UUID.DEVICE_OBJECT_TYPE) };
      }
      case OTS_UUID.OBJECT_SIZE: {
        const name = this.selectedName();
        if (name === null) return { error: 'no object selected' };
        const bytes = this.objectBytes(name);
        // Current size then allocated size, both uint32 little-endian.
        const value = new Uint8Array(8);
        writeU32(value, 0, bytes.length);
        writeU32(value, 4, bytes.length);
        return { value };
      }
      case OTS_UUID.OBJECT_ID: {
        if (this.selectedIndex < 0) return { error: 'no object selected' };
        // 48-bit little-endian object id; ids start at 0x100 per the spec.
        const id = 0x100 + this.selectedIndex;
        const value = new Uint8Array(6);
        writeU32(value, 0, id);
        return { value };
      }
      case OTS_UUID.OBJECT_PROPERTIES: {
        const descriptor = this.selected();
        if (!descriptor) return { error: 'no object selected' };
        const value = new Uint8Array(4);
        // read always; write only for the client-writable objects.
        writeU32(value, 0, descriptor.writable ? (1 << 1) | (1 << 2) : 1 << 1);
        return { value };
      }
      default:
        return { error: `characteristic ${short} is not readable` };
    }
  }

  /** @returns {{ok: true}|{error: string}} — the ack for mstWriteCharacteristic. */
  writeCharacteristic(uuid, bytes) {
    const short = normalizeUuid(uuid);
    if (this.faults.writeError && normalizeUuid(this.faults.writeError) === short) {
      return { error: `write to ${short} rejected by fault injection` };
    }

    switch (short) {
      case OTS_UUID.OLCP:
        return this._handleOlcp(bytes);
      case OTS_UUID.OACP:
        return this._handleOacp(bytes);
      case OTS_UUID.OBJECT_DATA:
        return this._handleObjectData(bytes);
      default:
        return { error: `characteristic ${short} is not writable` };
    }
  }

  setSubscription(uuid, enable, kind) {
    const short = normalizeUuid(uuid);
    if (enable) {
      this.subscriptions.set(short, kind);
    } else {
      this.subscriptions.delete(short);
    }
    return { ok: true };
  }

  // --- Control points -------------------------------------------------------

  _handleOlcp(bytes) {
    if (!bytes || bytes.length < 1) return { error: 'empty OLCP write' };
    const opcode = bytes[0];
    let result = OLCP_RESULT.SUCCESS;
    const last = this.objects.length - 1;

    switch (opcode) {
      case OLCP_OP.FIRST:
        if (last < 0) result = OLCP_RESULT.NO_OBJECT;
        else this.selectedIndex = 0;
        break;
      case OLCP_OP.LAST:
        if (last < 0) result = OLCP_RESULT.NO_OBJECT;
        else this.selectedIndex = last;
        break;
      case OLCP_OP.NEXT:
        if (this.selectedIndex < 0) result = OLCP_RESULT.NO_OBJECT;
        else if (this.selectedIndex >= last) result = OLCP_RESULT.OUT_OF_BOUNDS;
        else this.selectedIndex += 1;
        break;
      case OLCP_OP.PREVIOUS:
        if (this.selectedIndex <= 0) result = OLCP_RESULT.OUT_OF_BOUNDS;
        else this.selectedIndex -= 1;
        break;
      default:
        result = OLCP_RESULT.OPCODE_NOT_SUPPORTED;
        break;
    }

    if (this.faults.olcpResult !== undefined) result = this.faults.olcpResult;
    if (!this.faults.silentOlcp) {
      this._notify(OTS_UUID.OLCP, Uint8Array.of(OLCP_OP.RESPONSE, opcode, result));
    }
    return { ok: true };
  }

  _handleOacp(bytes) {
    if (!bytes || bytes.length < 1) return { error: 'empty OACP write' };
    const opcode = bytes[0];
    const name = this.selectedName();
    let result = OACP_RESULT.SUCCESS;
    let stream = null;

    const descriptor = this.selected();

    if (opcode === OACP_OP.READ) {
      if (name === null) {
        result = OACP_RESULT.INVALID_OBJECT;
      } else if (bytes.length < 9) {
        result = OACP_RESULT.INVALID_PARAMETER;
      } else {
        const offset = readU32(bytes, 1);
        const length = readU32(bytes, 5);
        const object = this.objectBytes(name);
        if (offset + length > object.length) {
          result = OACP_RESULT.INVALID_PARAMETER;
        } else {
          stream = object.slice(offset, offset + length);
        }
      }
    } else if (opcode === OACP_OP.WRITE) {
      if (name === null) {
        result = OACP_RESULT.INVALID_OBJECT;
      } else if (!descriptor.writable) {
        // Board-owned objects are read-only to clients (see OTS_OBJECTS).
        result = OACP_RESULT.PROCEDURE_NOT_PERMITTED;
      } else if (bytes.length < 10) {
        result = OACP_RESULT.INVALID_PARAMETER;
      } else {
        const offset = readU32(bytes, 1);
        const length = readU32(bytes, 5);
        this.pendingWrite = { descriptor, offset, length, received: [], receivedBytes: 0 };
      }
    } else {
      result = OACP_RESULT.OPCODE_NOT_SUPPORTED;
    }

    if (this.faults.oacpResult !== undefined) result = this.faults.oacpResult;
    if (result !== OACP_RESULT.SUCCESS) {
      this.pendingWrite = null;
      stream = null;
    }

    if (!this.faults.silentOacp) {
      this._notify(OTS_UUID.OACP, Uint8Array.of(OACP_OP.RESPONSE, opcode, result));
    }
    if (stream) this._streamObjectData(stream);
    return { ok: true };
  }

  /** Chunked content transfer: ObjectData notifications, one ATT payload each. */
  _streamObjectData(bytes) {
    const chunks = [];
    for (let i = 0; i < bytes.length; i += this.chunkSize) {
      chunks.push(bytes.slice(i, i + this.chunkSize));
    }
    const limit =
      this.faults.dropDataChunksAfter === undefined
        ? chunks.length
        : Math.min(chunks.length, this.faults.dropDataChunksAfter);

    chunks.slice(0, limit).forEach((chunk, index) => {
      this.transport.schedule(
        () => this._deliver(OTS_UUID.OBJECT_DATA, chunk),
        index * (this.faults.chunkDelayMs || 0)
      );
    });
  }

  /** Client -> board content, accumulated until the OACP WRITE length is met. */
  _handleObjectData(bytes) {
    if (!this.pendingWrite) return { error: 'ObjectData write with no OACP WRITE in flight' };
    const pending = this.pendingWrite;
    pending.received.push(bytes);
    pending.receivedBytes += bytes.length;
    if (pending.receivedBytes < pending.length) return { ok: true };

    const assembled = new Uint8Array(pending.length);
    let offset = 0;
    for (const chunk of pending.received) {
      const space = pending.length - offset;
      if (space <= 0) break;
      const slice = chunk.length > space ? chunk.slice(0, space) : chunk;
      assembled.set(slice, offset);
      offset += slice.length;
    }
    this.pendingWrite = null;

    let error = null;
    try {
      error = applyObjectWrite(this.state, pending.descriptor, assembled);
    } catch (e) {
      error = `failed to decode ${pending.descriptor.name} write: ${e.message}`;
    }
    this.appliedWrites.push({ name: pending.descriptor.name, bytes: assembled, error });
    return error ? { error } : { ok: true };
  }

  // --- Delivery -------------------------------------------------------------

  /**
   * Queues a notification. Scheduled rather than emitted inline because on real
   * hardware the ATT write response necessarily precedes the peer's indication,
   * and the radio only acks after writeCharacteristic() returns.
   */
  _notify(uuid, bytes) {
    this.transport.schedule(() => this._deliver(uuid, bytes), 0);
  }

  /** Delivers a notification if the client is still subscribed to it. */
  _deliver(uuid, bytes) {
    const short = normalizeUuid(uuid);
    if (this.strictSubscriptions && !this.subscriptions.has(short)) {
      // Real firmware cannot push to a characteristic the client never
      // subscribed to. Recorded so a test can prove the client subscribed.
      this.droppedNotifications.push({ uuid: short, length: bytes.length });
      return;
    }
    this.transport.sendNotification(uuid, bytes);
  }
}
