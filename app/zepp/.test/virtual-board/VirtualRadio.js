// The link layer between the mocked ble.mst* API and a VirtualBoard: scanning,
// connect/disconnect, GATT routing, latency and fault injection.
//
// It is deliberately strict — a GATT operation against an address with no live
// link fails, and a characteristic the board does not expose fails — because
// the whole point of the harness is to make the app's transport assumptions
// visible instead of letting them pass silently.

import { normalizeUuid, OTS_UUID } from './VirtualBoard';
import { realClearTimeout } from '../mocks/globals';

const CANONICAL_SUFFIX = '-0000-1000-8000-00805F9B34FB';

function toBytes(value) {
  if (value instanceof Uint8Array) return value;
  if (value instanceof ArrayBuffer) return new Uint8Array(value);
  if (ArrayBuffer.isView(value)) return new Uint8Array(value.buffer, value.byteOffset, value.byteLength);
  if (Array.isArray(value)) return Uint8Array.from(value);
  return new Uint8Array(0);
}

function hex(bytes) {
  return Array.from(bytes, b => b.toString(16).padStart(2, '0')).join('');
}

export class VirtualRadio {
  /**
   * @param {object} [config]
   * @param {Array}  [config.devices]     [{ address, name, board, rssi, serviceData }]
   * @param {object} [config.faults]      shared with the board (see VirtualBoard)
   * @param {'canonical'|'short'} [config.uuidFormat]  how the stack reports UUIDs in events
   * @param {object} [config.latency]     { scan, connect, gatt, notify } in board-ms
   * @param {number} [config.advertIntervalMs]  re-advertise every N board-ms while scanning
   */
  constructor(config = {}) {
    this.faults = config.faults || {};
    this.uuidFormat = config.uuidFormat || 'canonical';
    this.latency = { scan: 5, connect: 5, gatt: 1, notify: 1, ...(config.latency || {}) };
    this.advertIntervalMs = config.advertIntervalMs || 0;
    this.devices = (config.devices || []).map(d => ({ rssi: -55, ...d }));
    this.devices.forEach(d => {
      if (!d.board) throw new Error(`virtual device ${d.address} has no board`);
      d.board.attach(this._transportFor(d));
    });

    // Per-instance connect override, so a fault fixture shared between harnesses
    // is never mutated (see disconnect()).
    this.abortedConnect = false;

    this.deliver = null;
    this.detached = false;
    this.links = new Map(); // address -> { profileBuilt }
    this.scanning = false;
    this.scanCallback = null;

    // Every timer this radio schedules, so detach() can stop the whole thing.
    this.timers = new Set();

    // Observability. `calls` is every mst* invocation in order; `script` is the
    // request/response pairing a recorded trace replays from (see trace.js).
    this.calls = [];
    this.script = [];
    this._currentScriptEntry = null;
  }

  /** Called by the @zos/ble mock, which owns the app's permanent callbacks. */
  attach(deliver) {
    this.deliver = deliver;
  }

  /**
   * Stops the radio for good: no further events reach the app's (permanently
   * registered) listeners, and every pending timer is cancelled.
   *
   * This matters more than it looks. OtsClient registers its arrived/notification
   * callbacks once at module load and they are never cleared, so a radio left
   * running after teardown would keep delivering into the NEXT test in the same
   * file — a stale 2AC0 read or 2AC5 indication can satisfy an in-flight
   * _readChar/_awaitControlResponse and make a test pass for the wrong reason.
   */
  detach() {
    this.detached = true;
    this.deliver = null;
    this.scanning = false;
    this.scanCallback = null;
    this.timers.forEach(handle => realClearTimeout(handle));
    this.timers.clear();
  }

  boardFor(address) {
    const device = this.devices.find(d => d.address === address);
    return device ? device.board : null;
  }

  isConnected(address) {
    return this.links.has(address);
  }

  // --- mst* surface ---------------------------------------------------------

  startScan(options, callback) {
    this._record('mstStartScan', { timeout: options && options.timeout });
    // faults.scan: 'refuse' makes the call return false, as the documented
    // mstStartScan does when it cannot start. 'fail' calls the fail callback
    // with faults.scanError (or a default text) instead.
    if (this.faults.scan === 'refuse') return false;
    if (this.faults.scan === 'fail') {
      this._schedule(() => this._fail(options, this.faults.scanError || 'scan could not start'), 0);
      return true;
    }
    this.scanning = true;
    this.scanCallback = callback;
    this.devices.forEach((device, index) => this._advertise(device, callback, this.latency.scan * (index + 1)));
    return true;
  }

  /**
   * Emits one advertisement, then re-arms while scanning if advertIntervalMs is
   * set. A real BLE Central re-reports the same peripheral continuously, which is
   * what drives BleManager's dedupe and the device list's full redraw.
   */
  _advertise(device, callback, delay) {
    this._schedule(() => {
      if (!this.scanning || this.scanCallback !== callback) return;
      // Shape of a Zepp OS BLE Central scan result. This is the least documented
      // part of the surface — see .test/README.md for how to refresh it from a
      // real capture.
      callback({
        dev_id: device.address,
        address: device.address,
        name: device.name,
        rssi: device.rssi,
        service_data: device.serviceData || []
      });
      if (this.advertIntervalMs > 0) this._advertise(device, callback, this.advertIntervalMs);
    }, delay);
  }

  stopScan() {
    this._record('mstStopScan', {});
    this.scanning = false;
    this.scanCallback = null;
  }

  connect(options) {
    const address = options.address;
    this._record('mstConnect', { address });
    const device = this.devices.find(d => d.address === address);
    const delay = this.faults.connectDelayMs === undefined ? this.latency.connect : this.faults.connectDelayMs;

    this._schedule(() => {
      if (!device || this.faults.connect === 'fail') {
        this._fail(options, device ? 'connection refused by fault injection' : `no device at ${address}`);
        return;
      }
      if (this.faults.connect === 'silent' || this.abortedConnect) return; // never completes
      this.links.set(address, { profileBuilt: false });
      device.board.resetSession();
      this._succeed(options);
    }, delay);
  }

  disconnect(options) {
    const address = options && options.address;
    this._record('mstDisconnect', { address });
    // Note: a disconnect issued while a connect is still in flight does NOT
    // cancel it by default. BleManager defends against exactly that race
    // (_connectGen), so the harness must be able to produce it.
    if (this.faults.abortPendingConnectOnDisconnect) this.abortedConnect = true;
    const board = this.boardFor(address);
    if (board) board.resetSession();
    this.links.delete(address);
    this._schedule(() => this._succeed(options), this.latency.connect);
  }

  buildProfile(options) {
    const address = options.address;
    this._record('mstBuildProfile', { address, services: options.services });
    this._schedule(() => {
      const link = this.links.get(address);
      if (!link) {
        this._fail(options, `no link to ${address}`);
        return;
      }
      if (this.faults.buildProfile === 'fail') {
        this._fail(options, 'service discovery failed by fault injection');
        return;
      }
      link.profileBuilt = true;
      this._succeed(options);
    }, this.latency.gatt);
  }

  readCharacteristic(options) {
    const { address, characteristicUuid } = options;
    this._record('mstReadCharacteristic', { address, uuid: characteristicUuid });
    const board = this._requireLink(options);
    if (!board) return;
    this._schedule(() => {
      const result = board.readCharacteristic(characteristicUuid);
      if (result.error) {
        this._fail(options, result.error);
        return;
      }
      this._succeed(options);
      this._sendValue(address, characteristicUuid, result.value);
    }, this.latency.gatt);
  }

  writeCharacteristic(options) {
    const { address, characteristicUuid } = options;
    const bytes = toBytes(options.value);
    this._record('mstWriteCharacteristic', { address, uuid: characteristicUuid, hex: hex(bytes) });
    const board = this._requireLink(options);
    if (!board) return;
    // faults.silentWrite names a characteristic whose writes the stack drops:
    // neither callback fires and the board never sees the bytes.
    if (this.faults.silentWrite && normalizeUuid(this.faults.silentWrite) === normalizeUuid(characteristicUuid)) {
      return;
    }
    this._schedule(() => {
      const result = board.writeCharacteristic(characteristicUuid, bytes);
      if (result.error) {
        this._fail(options, result.error);
        return;
      }
      // The ATT write response precedes any indication the peer sends back, so
      // the board schedules its control-point responses rather than emitting
      // them inline (see VirtualBoard._notify).
      this._succeed(options);
    }, this.latency.gatt);
  }

  prepareNotification(options, kind) {
    const { address, characteristicUuid, enable } = options;
    this._record(kind === 'indicate' ? 'mstPrepareIndicateChar' : 'mstPrepareNotificationChar', {
      address,
      uuid: characteristicUuid,
      enable
    });
    const board = this._requireLink(options);
    if (!board) return;
    this._schedule(() => {
      board.setSubscription(characteristicUuid, !!enable, kind);
      this._succeed(options);
    }, this.latency.gatt);
  }

  // --- Internals ------------------------------------------------------------

  _requireLink(options) {
    const address = options.address;
    const link = this.links.get(address);
    const board = this.boardFor(address);
    if (!link || !board) {
      this._schedule(() => this._fail(options, `no GATT link to ${address}`), this.latency.gatt);
      return null;
    }
    if (!link.profileBuilt) {
      this._schedule(() => this._fail(options, `GATT profile not built for ${address}`), this.latency.gatt);
      return null;
    }
    return board;
  }

  _transportFor(device) {
    return {
      // Responses the board schedules are attributed to the request that caused
      // them, not to whatever request happens to be current when they fire.
      schedule: (fn, ms) => {
        const owner = this._currentScriptEntry;
        this._schedule(() => {
          const previous = this._currentScriptEntry;
          this._currentScriptEntry = owner;
          try {
            fn();
          } finally {
            this._currentScriptEntry = previous;
          }
        }, ms === undefined ? this.latency.notify : ms);
      },
      sendNotification: (uuid, bytes) => this._sendNotification(device.address, uuid, bytes),
      sendValue: (uuid, bytes) => this._sendValue(device.address, uuid, bytes)
    };
  }

  _sendValue(address, uuid, bytes) {
    if (this.detached) return;
    const event = this._event(address, uuid, bytes);
    this._recordResponse('value', event);
    if (this.deliver) this.deliver.deliverValue(event);
  }

  _sendNotification(address, uuid, bytes) {
    if (this.detached) return;
    const event = this._event(address, uuid, bytes);
    this._recordResponse('notify', event);
    if (this.deliver) this.deliver.deliverNotification(event);
  }

  _event(address, uuid, bytes) {
    const copy = Uint8Array.from(bytes);
    return {
      address,
      dev_id: address,
      serviceUuid: this._formatUuid(OTS_UUID.SERVICE),
      characteristicUuid: this._formatUuid(uuid),
      // Real stacks hand back an ArrayBuffer; OtsClient wraps it in a Uint8Array.
      value: copy.buffer
    };
  }

  /**
   * Many stacks report 16-bit assigned UUIDs in their canonical 128-bit form.
   * OtsClient's uuidMatches() is written for that, so the harness defaults to it.
   */
  _formatUuid(uuid) {
    const short = normalizeUuid(uuid);
    if (this.uuidFormat === 'short' || short.length !== 4) return short;
    return `0000${short}${CANONICAL_SUFFIX}`;
  }

  _schedule(fn, ms) {
    if (this.detached) return;
    // Global setTimeout — the harness scales it, so board latencies compress by
    // exactly the same factor as the app's own delays.
    const handle = setTimeout(() => {
      this.timers.delete(handle);
      if (this.detached) return;
      fn();
    }, ms || 0);
    this.timers.add(handle);
  }

  _succeed(options) {
    if (options && typeof options.success === 'function') options.success();
  }

  _fail(options, message) {
    if (options && typeof options.fail === 'function') options.fail(message);
  }

  _record(fn, payload) {
    this.calls.push({ fn, ...payload });
    this._currentScriptEntry = { request: { fn, ...payload }, responses: [] };
    this.script.push(this._currentScriptEntry);
    return this._currentScriptEntry;
  }

  _recordResponse(channel, event) {
    if (!this._currentScriptEntry) return;
    this._currentScriptEntry.responses.push({
      channel,
      uuid: normalizeUuid(event.characteristicUuid),
      hex: hex(new Uint8Array(event.value))
    });
  }

  callsTo(fn) {
    return this.calls.filter(c => c.fn === fn);
  }
}
