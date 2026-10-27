import * as ble from '@zos/ble';
import {
  boltModeToDomain,
  languageToDomain,
  screenBrightnessToDomain,
  screenOffDelayToDomain,
  toDomainDevice,
  toProtocolConfig,
  trimProtobufPadding
} from '../protocol/OtsModelMapper';
import { snapshotpb } from '../protocol/proto';

// Firmware + the KMP reference client exchange BARE per-object protobuf messages
// (no outer Message envelope). Decode/encode each OTS object as its own type.
const { Device, DeviceConfig, Profiles } = snapshotpb.v1;
import { OACP, OLCP, RESULT_SUCCESS } from '../protocol/OtsOpcodes';

const SUBSCRIBE_TIMEOUT_MS = 5000;
const READ_TIMEOUT_MS = 3000;
const WRITE_TIMEOUT_MS = 3000;

// Upper bound for one queued GATT op. Each op has its own shorter timeout, so this watchdog fires
// only when an op fails to settle by its own rules. Without it, one op that never settles blocks
// every later op on the client with no error.
const GATT_OP_WATCHDOG_MS = 8000;

// The board exposes three objects. The OLCP walk stops after this many objects, so a board that
// never reports the end of its list cannot hold the client in the walk.
const MAX_OBJECTS_IN_WALK = 16;
const SERVICE_UUID = "1825";

// Post-write OTS selection: the vendor firmware commits a just-written object
// asynchronously and ignores OLCP during that window, so a selection issued
// mid-commit is dropped (not queued). Settle then re-issue, mirroring the KMP
// selectObjectByNameAfterCommit.
const SELECT_AFTER_COMMIT_SETTLE_MS = 750;
const SELECT_AFTER_COMMIT_ATTEMPTS = 3;
// The fewest profiles a board keeps. Mirrors Device.MIN_PROFILE_COUNT in core:model.
const MIN_PROFILE_COUNT = 1;

// Read-path selection: an individual metadata read can transiently glitch during
// the OLCP First/Next walk, so retry the whole selection after a brief settle
// (the first attempt runs immediately). Mirrors the KMP selectObjectByNameForRead.
const SELECT_FOR_READ_ATTEMPTS = 3;
const SELECT_FOR_READ_RETRY_MS = 150;

const OtsUuids = {
  Service: "1825",
  Feature: "2ABD",
  ObjectName: "2ABE",
  ObjectType: "2ABF",
  ObjectSize: "2AC0",
  ObjectId: "2AC3",
  ObjectProperties: "2AC4",
  Oacp: "2AC5",
  Olcp: "2AC6",
  DeviceObjectType: "00000001-5350-4221-0000-5350425f4f54",
  ObjectData: "00000002-5350-4221-0000-5350425f4f54"
};

// Module-level listener lists. The @zos/ble master callbacks can only be
// registered once, so the OtsClient instances share these; each listener closure
// filters by both characteristic UUID and (defensively) the device address so a
// second connection could never resolve the wrong client's pending waiter.
const valueListeners = [];
const notificationListeners = [];

if (typeof ble.mstOnCharaValueArrived === 'function') {
  ble.mstOnCharaValueArrived((res) => {
    // Snapshot the list: listeners splice themselves out during cleanup, and
    // mutating mid-forEach would shift indices and skip the next listener.
    valueListeners.slice().forEach(listener => listener(res));
  });
}

if (typeof ble.mstOnCharaNotification === 'function') {
  ble.mstOnCharaNotification((res) => {
    notificationListeners.slice().forEach(listener => listener(res));
  });
}

function addValueListener(fn) {
  valueListeners.push(fn);
}

function removeValueListener(fn) {
  const index = valueListeners.indexOf(fn);
  if (index !== -1) valueListeners.splice(index, 1);
}

function addNotificationListener(fn) {
  notificationListeners.push(fn);
}

function removeNotificationListener(fn) {
  const index = notificationListeners.indexOf(fn);
  if (index !== -1) notificationListeners.splice(index, 1);
}

// An error for a transport op that got no answer in time. A caller uses isTransportTimeout() to
// find a link that is possibly lost.
function timeoutError(message) {
  const err = new Error(message);
  err.isTimeout = true;
  return err;
}

export function isTransportTimeout(err) {
  return !!(err && err.isTimeout);
}

// Decodes an OTS object name. The names are ASCII, so each byte is one character. The Zepp OS
// runtime does not always supply TextDecoder, so do not use it here. Trailing NULs from a
// fixed-width firmware buffer are removed.
export function decodeObjectName(bytes) {
  let out = '';
  for (let i = 0; i < bytes.length; i++) {
    if (bytes[i] !== 0) out += String.fromCharCode(bytes[i]);
  }
  return out;
}

// Runs fn() and rejects when it does not settle in [ms].
function withWatchdog(fn, ms, label) {
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      reject(timeoutError(`${label} did not settle in ${ms} ms`));
    }, ms);
    Promise.resolve()
      .then(fn)
      .then((value) => {
        clearTimeout(timer);
        resolve(value);
      }, (err) => {
        clearTimeout(timer);
        reject(err);
      });
  });
}

function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

function uuidMatches(received, target) {
  const r = (received || '').toUpperCase();
  const t = (target || '').toUpperCase();
  // Reject empty/missing UUIDs — "ANY".indexOf("") === 0 would otherwise match
  // every characteristic when an event arrives without a UUID field.
  if (!r || !t) return false;
  // Substring (either direction) — a 16-bit assigned UUID like "2AC5" sits at
  // offset 4-7 inside its 128-bit canonical form
  // (0000XXXX-0000-1000-8000-00805F9B34FB), so endsWith would miss it when the
  // stack reports canonical UUIDs. The vendor 128-bit UUIDs still match via
  // exact equality, so substring matching introduces no false positives here.
  return r === t || r.indexOf(t) !== -1 || t.indexOf(r) !== -1;
}

function eventAddress(res) {
  return (res && (res.address || res.dev_id || res.dev || res.deviceSource)) || null;
}

// Only reject an event on a *definite* address mismatch. Many @zos/ble stacks do
// not tag characteristic events with an address; with a single connection those
// still route correctly, so an absent address must not filter the event out.
function addressMatches(res, address) {
  if (!address) return true;
  const ra = eventAddress(res);
  if (!ra) return true;
  return String(ra).toUpperCase() === String(address).toUpperCase();
}

export class OtsClient {
  constructor(address) {
    // BLE connect address (the scan-provided MAC), NOT the board's protobuf UUID.
    this.address = address;
    // Serializes GATT ops: the stack allows one read/write/control-point op in
    // flight per connection (mirrors the KMP gattMutex). Every low-level op
    // chains onto this queue so back-to-back ops never race.
    this._gattQueue = Promise.resolve();
    this._prepared = false;
    // The decoded protobuf Profiles from the most recent board read. Carried into
    // toProtocolConfig on write so the board-owned per-profile field board_auto_off
    // is preserved rather than reset by a full-object rewrite.
    // Null until the first successful read.
    this._lastReadProfiles = null;
    // Set by close(). A closed client sends no more GATT ops: an op that is queued or new rejects.
    // A later connect to the same address gets a new link, and a late op of this client must not
    // reach it.
    this._closed = false;
  }

  get closed() {
    return this._closed;
  }

  // Runs `fn` (which returns a Promise for one GATT op) only once every prior
  // queued op has settled, so exactly one op is outstanding at a time. A watchdog
  // rejects an op that does not settle, so the queue always moves on.
  _serialize(fn) {
    const run = () => {
      if (this._closed) return Promise.reject(new Error('OtsClient is closed'));
      return withWatchdog(fn, GATT_OP_WATCHDOG_MS, 'GATT op');
    };
    const result = this._gattQueue.then(run, run);
    this._gattQueue = result.then(() => {}, () => {});
    return result;
  }

  // Promise-based GATT operations (each serialized through _gattQueue)
  _readChar(charUuid) {
    return this._serialize(() => new Promise((resolve, reject) => {
      const timeout = setTimeout(() => {
        cleanup();
        reject(timeoutError(`Read char ${charUuid} timed out`));
      }, READ_TIMEOUT_MS);

      const onValue = (res) => {
        if (!addressMatches(res, this.address)) return;
        const resUuid = (res.characteristicUuid || res.charaUuid || "").toUpperCase();
        const targetUuid = charUuid.toUpperCase();
        if (uuidMatches(resUuid, targetUuid)) {
          cleanup();
          resolve(new Uint8Array(res.value));
        }
      };

      const cleanup = () => {
        clearTimeout(timeout);
        removeValueListener(onValue);
      };

      addValueListener(onValue);
      ble.mstReadCharacteristic({
        address: this.address,
        serviceUuid: SERVICE_UUID,
        characteristicUuid: charUuid,
        fail: (err) => {
          cleanup();
          reject(new Error(`Read char ${charUuid} failed: ${err}`));
        }
      });
    }));
  }

  _writeChar(charUuid, bytes) {
    return this._serialize(() => new Promise((resolve, reject) => {
      // A write the stack drops calls neither callback. The timeout rejects it, so the GATT queue
      // moves on and the caller gets an error.
      let settled = false;
      const timeout = setTimeout(() => {
        if (settled) return;
        settled = true;
        reject(timeoutError(`Write char ${charUuid} timed out`));
      }, WRITE_TIMEOUT_MS);
      const once = (fn) => (arg) => {
        if (settled) return;
        settled = true;
        clearTimeout(timeout);
        fn(arg);
      };
      ble.mstWriteCharacteristic({
        address: this.address,
        serviceUuid: SERVICE_UUID,
        characteristicUuid: charUuid,
        value: bytes.buffer || bytes,
        success: once(() => resolve()),
        fail: once((err) => reject(new Error(`Write char ${charUuid} failed: ${err}`)))
      });
    }));
  }

  _subscribeChar(charUuid, enable, preferIndicate = false) {
    return this._serialize(() => new Promise((resolve, reject) => {
      // A CCCD write the stack drops calls neither callback. Without a bound, prepare() never
      // settles, the GATT queue stays blocked and close() can never run its unsubscribes.
      let settled = false;
      const timeout = setTimeout(() => {
        if (settled) return;
        settled = true;
        reject(timeoutError(`Subscribe char ${charUuid} timed out`));
      }, SUBSCRIBE_TIMEOUT_MS);
      const once = (fn) => (arg) => {
        if (settled) return;
        settled = true;
        clearTimeout(timeout);
        fn(arg);
      };
      const options = {
        address: this.address,
        serviceUuid: SERVICE_UUID,
        characteristicUuid: charUuid,
        enable: enable,
        success: once(() => resolve()),
        fail: once((err) => reject(new Error(`Subscribe char ${charUuid} failed: ${err}`)))
      };

      if (preferIndicate) {
        if (typeof ble.mstPrepareIndicateChar === 'function') {
          ble.mstPrepareIndicateChar(options);
        } else if (typeof ble.mstPrepareNotificationChar === 'function') {
          ble.mstPrepareNotificationChar(options);
        } else {
          once(resolve)();
        }
      } else {
        if (typeof ble.mstPrepareNotificationChar === 'function') {
          ble.mstPrepareNotificationChar(options);
        } else if (typeof ble.mstPrepareIndicateChar === 'function') {
          ble.mstPrepareIndicateChar(options);
        } else {
          once(resolve)();
        }
      }
    }));
  }

  // Best-effort teardown: a failed unsubscribe (fail callback on a flaky link)
  // must never override a successful read/write result, so swallow it.
  async _safeUnsubscribe(charUuid, preferIndicate) {
    try {
      await this._subscribeChar(charUuid, false, preferIndicate);
    } catch (e) {
      console.log(`Best-effort unsubscribe of ${charUuid} failed:`, e);
    }
  }

  // Subscribe OACP/OLCP indications and the vendor ObjectData notification ONCE
  // per connection and keep them for the client's lifetime (torn down in close).
  //
  // The subscriptions are NOT toggled around each object read: enabling a
  // notification immediately before each OACP READ intermittently wedges the BLE
  // stack, surfacing as a timeout on the 2nd+ object (the KMP client hit this and
  // moved subscription into prepare()/close()). The firmware only notifies
  // ObjectData in response to an OACP READ, so nothing streams between reads.
  async prepare() {
    if (this._prepared) return;
    await this._subscribeChar(OtsUuids.Oacp, true, true);
    await sleep(1000);
    await this._subscribeChar(OtsUuids.Olcp, true, true);
    await sleep(1000);
    await this._subscribeChar(OtsUuids.ObjectData, true);
    await sleep(1000);
    this._prepared = true;
  }

  // Marks the client closed, so it sends no more GATT ops.
  //
  // With options.linkDropped (the caller disconnects the link next), no CCCD
  // unsubscribe is sent: the subscriptions end with the link. The disable writes
  // would otherwise run after mstDisconnect, and on a quick reconnect to the same
  // address they could turn off the indications of the new link.
  //
  // Without it, the subscriptions are released first (best-effort, so a teardown
  // error on a dropping link cannot propagate), then the client closes.
  async close(options = {}) {
    if (this._closed) return;
    this._prepared = false;
    if (!options.linkDropped) {
      await this._safeUnsubscribe(OtsUuids.ObjectData, false);
      await this._safeUnsubscribe(OtsUuids.Oacp, true);
      await this._safeUnsubscribe(OtsUuids.Olcp, true);
    }
    this._closed = true;
  }

  // Helper to wait for control point indication (OACP/OLCP)
  _awaitControlResponse(charUuid, sendAction) {
    return new Promise((resolve, reject) => {
      const timeout = setTimeout(() => {
        cleanup();
        reject(timeoutError(`Control point indication for ${charUuid} timed out`));
      }, 5000);

      const onNotification = (res) => {
        if (!addressMatches(res, this.address)) return;
        const resUuid = (res.characteristicUuid || res.charaUuid || "").toUpperCase();
        const targetUuid = charUuid.toUpperCase();
        if (uuidMatches(resUuid, targetUuid)) {
          cleanup();
          resolve(new Uint8Array(res.value));
        }
      };

      const cleanup = () => {
        clearTimeout(timeout);
        removeNotificationListener(onNotification);
      };

      // Subscribe first so the response can't race the write.
      addNotificationListener(onNotification);

      sendAction().catch(err => {
        cleanup();
        reject(err);
      });
    });
  }

  async _sendOlcp(opcode, params = new Uint8Array(0)) {
    const cmd = new Uint8Array(1 + params.length);
    cmd[0] = opcode;
    cmd.set(params, 1);

    const response = await this._awaitControlResponse(OtsUuids.Olcp, () => {
      return this._writeChar(OtsUuids.Olcp, cmd);
    });

    if (response.length < 3 || response[0] !== OLCP.RESPONSE_CODE || response[1] !== opcode) {
      throw new Error(`OLCP response failed for opcode ${opcode}`);
    }
    return response[2]; // Result code: RESULT_SUCCESS = Success
  }

  async _sendOacp(opcode, params = new Uint8Array(0)) {
    const cmd = new Uint8Array(1 + params.length);
    cmd[0] = opcode;
    cmd.set(params, 1);

    const response = await this._awaitControlResponse(OtsUuids.Oacp, () => {
      return this._writeChar(OtsUuids.Oacp, cmd);
    });

    if (response.length < 3 || response[0] !== OACP.RESPONSE_CODE || response[1] !== opcode) {
      throw new Error(`OACP response failed for opcode ${opcode}`);
    }
    return response[2]; // Result code: RESULT_SUCCESS = Success
  }

  // Walks the OTS object list via OLCP First/Next and leaves the object whose
  // name matches selected. OLCP is already subscribed in prepare().
  async selectObjectByName(name) {
    console.log(`Selecting OTS object by name: ${name}`);

    // 1. Go to FIRST object
    let result = await this._sendOlcp(OLCP.FIRST); // FIRST
    if (result !== RESULT_SUCCESS) return false;

    for (let visited = 0; visited < MAX_OBJECTS_IN_WALK; visited++) {
      // 2. Read Object Name
      const nameBytes = await this._readChar(OtsUuids.ObjectName);
      const objName = decodeObjectName(nameBytes);
      console.log(`Checking OTS object name: ${objName}`);

      if (objName === name) {
        return true;
      }

      // 3. Go to NEXT object
      result = await this._sendOlcp(OLCP.NEXT); // NEXT
      if (result !== RESULT_SUCCESS) {
        return false; // Past last object
      }
    }
    console.log(`OTS object walk stopped after ${MAX_OBJECTS_IN_WALK} objects`);
    return false;
  }

  // Read-path selection with a first immediate attempt and brief retries; rides
  // out a transient metadata-read glitch during the OLCP walk.
  async _selectObjectByNameForRead(name) {
    for (let attempt = 0; attempt < SELECT_FOR_READ_ATTEMPTS; attempt++) {
      if (await this.selectObjectByName(name)) return true;
      if (attempt < SELECT_FOR_READ_ATTEMPTS - 1) await sleep(SELECT_FOR_READ_RETRY_MS);
    }
    return false;
  }

  // Post-write selection: settle before each attempt to ride out the firmware's
  // asynchronous commit window (during which OLCP is ignored).
  async _selectObjectByNameAfterCommit(name) {
    for (let attempt = 0; attempt < SELECT_AFTER_COMMIT_ATTEMPTS; attempt++) {
      await sleep(SELECT_AFTER_COMMIT_SETTLE_MS);
      if (await this.selectObjectByName(name)) return true;
    }
    return false;
  }

  async readSelectedObjectBytes() {
    // 1. Read Object Size
    const sizeBytes = await this._readChar(OtsUuids.ObjectSize);
    // Current size is first 4 bytes, Little-Endian
    const expectedSize = sizeBytes[0] | (sizeBytes[1] << 8) | (sizeBytes[2] << 16) | (sizeBytes[3] << 24);
    console.log(`OTS Object Size: ${expectedSize} bytes`);

    if (expectedSize === 0) return new Uint8Array(0);

    // ObjectData notifications and OACP indications are already subscribed in
    // prepare() and stay on for the client's lifetime — no per-read toggle.
    const chunks = [];
    let receivedBytes = 0;

    let cleanupData = () => {};
    const dataPromise = new Promise((resolve, reject) => {
      const timeout = setTimeout(() => {
        cleanup();
        reject(timeoutError("OTS Content transfer timed out"));
      }, 10000);

      const onDataNotification = (res) => {
        if (!addressMatches(res, this.address)) return;
        const resUuid = (res.characteristicUuid || res.charaUuid || "").toUpperCase();
        const targetUuid = OtsUuids.ObjectData.toUpperCase();
        if (uuidMatches(resUuid, targetUuid)) {
          // A chunk after the object is complete belongs to no transfer; drop it.
          if (receivedBytes >= expectedSize) return;
          const chunk = new Uint8Array(res.value);
          chunks.push(chunk);
          receivedBytes += chunk.length;
          if (receivedBytes >= expectedSize) {
            console.log(`OTS transfer complete: ${receivedBytes}/${expectedSize} bytes`);
            cleanup();
            resolve();
          }
        }
      };

      const cleanup = () => {
        clearTimeout(timeout);
        removeNotificationListener(onDataNotification);
      };

      cleanupData = cleanup;
      addNotificationListener(onDataNotification);
    });

    try {
      // 2. Issue OACP READ (offset 0, length = expectedSize)
      const payload = new Uint8Array(8);
      // Offset: 0 (bytes 0..3)
      // Length: expectedSize (bytes 4..7)
      payload[4] = expectedSize & 0xFF;
      payload[5] = (expectedSize >> 8) & 0xFF;
      payload[6] = (expectedSize >> 16) & 0xFF;
      payload[7] = (expectedSize >> 24) & 0xFF;

      const result = await this._sendOacp(OACP.READ, payload); // READ
      if (result !== RESULT_SUCCESS) {
        throw new Error(`OACP READ handshake failed: code ${result}`);
      }

      // 3. Await data accumulation
      await dataPromise;

      // More bytes than the Object Size promised means the stream is shifted (a late chunk from
      // an earlier transfer, or a firmware fault); a cut copy would decode to wrong values.
      if (receivedBytes !== expectedSize) {
        throw new Error(`OTS transfer size mismatch: received ${receivedBytes}, expected ${expectedSize}`);
      }
      const finalBytes = new Uint8Array(expectedSize);
      let offset = 0;
      for (const chunk of chunks) {
        finalBytes.set(chunk, offset);
        offset += chunk.length;
      }
      return finalBytes;
    } finally {
      // Only remove the per-read content listener. The ObjectData/OACP GATT
      // subscriptions stay on until close() — this teardown is a synchronous
      // listener removal that can't reject and mask the result.
      cleanupData();
    }
  }

  // Orchestrator for loading device config and profile lists. Assumes prepare()
  // has already been called on this client.
  async readDeviceAndProfiles(onSuccess, onFailure, onPhase) {
    try {
      // 1. Read Device object (bare snapshotpb.v1.Device)
      const devSelected = await this._selectObjectByNameForRead("device");
      if (!devSelected) {
        throw new Error("OTS object 'device' not found");
      }
      const deviceBytes = await this.readSelectedObjectBytes();
      const trimmedDevBytes = trimProtobufPadding(deviceBytes);

      // Decode as a BARE per-object message (no Message envelope).
      // decode() returns a message or throws, so it needs no null check.
      const decodedDevice = Device.decode(trimmedDevBytes);
      if (onPhase) onPhase('deviceRead');

      // 2. Read Profiles object (bare snapshotpb.v1.Profiles)
      const profSelected = await this._selectObjectByNameForRead("profiles");
      if (!profSelected) {
        throw new Error("OTS object 'profiles' not found");
      }
      const profilesBytes = await this.readSelectedObjectBytes();
      const trimmedProfBytes = trimProtobufPadding(profilesBytes);
      const decodedProfiles = Profiles.decode(trimmedProfBytes);
      // Retain the board's profiles so a later write can carry forward the
      // board-owned per-profile fields the domain model does not track.
      this._lastReadProfiles = decodedProfiles;
      if (onPhase) onPhase('profilesRead');

      // 3. Read DeviceConfig object (active profile, boot text, language, screen settings) — a
      // separate writable object the board always exposes. A read/decode failure propagates to
      // the outer handler (failing the sync) rather than silently substituting defaults.
      const cfgSelected = await this._selectObjectByNameForRead("device_config");
      if (!cfgSelected) {
        throw new Error("OTS object 'device_config' not found");
      }
      const cfgBytes = await this.readSelectedObjectBytes();
      const decodedDeviceConfig = DeviceConfig.decode(trimProtobufPadding(cfgBytes));

      // Map to Domain Device
      const deviceName = "SnapshotPB Device";
      const domainDevice = toDomainDevice(decodedDevice, decodedDeviceConfig, decodedProfiles, deviceName, this.address);
      onSuccess(domainDevice);
    } catch (e) {
      onFailure(e);
    }
  }

  // On success, onSuccess receives { language, screenBrightness, screenOffDelay }: the values the
  // board reported after the write (null for one it reports none for). It receives null when that
  // read-back failed.
  async writeDeviceConfiguration(domainDevice, onSuccess, onFailure) {
    try {
      // A Profiles object with no profiles has zero length, and the board keeps at least one
      // profile. Refuse the write before device_config goes, so the board does not keep half a
      // save. Mirrors the guard in the KMP BluetoothConnectionEngine.
      if ((domainDevice.knownProfiles || []).length < MIN_PROFILE_COUNT) {
        throw new Error("The device has no profiles; the board keeps at least one");
      }
      // Encode domainDevice back to BARE protobuf (no Message envelope). Writes go
      // through the writable DeviceConfig (active profile, boot text, language, screen
      // settings) and Profiles
      // objects; the read-only Device is never written. Pass the last board read so
      // board-owned per-profile fields are carried forward (null before any read, in
      // which case the mapper falls back to defaults).
      const { deviceConfig, profiles } = toProtocolConfig(domainDevice, this._lastReadProfiles);
      const cfgBytes = DeviceConfig.encode(deviceConfig).finish();
      const profBytes = Profiles.encode(profiles).finish();

      // Write 'device_config' object
      const cfgSelected = await this._selectObjectByNameForRead("device_config");
      if (!cfgSelected) throw new Error("OTS object 'device_config' not found");
      await this._writeSelectedObjectBytes(cfgBytes);

      // Write 'profiles' object. The firmware commits the just-written
      // device_config asynchronously and drops any OLCP selection issued during
      // that window, so settle-and-retry the selection rather than failing the
      // whole write on one dropped selection (board left half-updated).
      const profSelected = await this._selectObjectByNameAfterCommit("profiles");
      if (!profSelected) throw new Error("OTS object 'profiles' not found");
      await this._writeSelectedObjectBytes(profBytes);

      // The board ignores a language or a brightness that it does not know, and it clamps the
      // screen-off delay, so a client must not assume that the write took effect. Read
      // device_config back for the values the board holds. The write itself succeeded, so a failed
      // read-back reports null instead of failing the save.
      let written = null;
      try {
        const cfgBack = await this._selectObjectByNameAfterCommit("device_config");
        if (!cfgBack) throw new Error("OTS object 'device_config' not found");
        const backBytes = await this.readSelectedObjectBytes();
        const backConfig = DeviceConfig.decode(trimProtobufPadding(backBytes));
        written = {
          language: languageToDomain(backConfig.language),
          screenBrightness: screenBrightnessToDomain(backConfig.screenBrightness),
          screenOffDelay: screenOffDelayToDomain(backConfig.screenOffDelay),
          boltMode: boltModeToDomain(backConfig.boltMode)
        };
      } catch (readBackError) {
        console.log("Read-back after the write failed:", readBackError);
      }

      onSuccess(written);
    } catch (e) {
      onFailure(e);
    }
  }

  async _writeSelectedObjectBytes(bytes) {
    // OTS Spec v1.0 standard write is over L2CAP. As a GATT-only fallback we write
    // chunks to the vendor ObjectData characteristic after the OACP WRITE
    // handshake. OACP is already subscribed in prepare().
    console.log(`Writing OTS object: ${bytes.length} bytes`);

    // Send OACP WRITE (offset 0, length = bytes.length, mode = Write/Patch (0x00))
    const payload = new Uint8Array(9);
    // Offset: 0 (bytes 0..3)
    // Length: bytes.length (bytes 4..7)
    const len = bytes.length;
    payload[4] = len & 0xFF;
    payload[5] = (len >> 8) & 0xFF;
    payload[6] = (len >> 16) & 0xFF;
    payload[7] = (len >> 24) & 0xFF;
    payload[8] = 0x00; // mode: Write/Patch

    const result = await this._sendOacp(OACP.WRITE, payload); // WRITE
    if (result !== RESULT_SUCCESS) {
      throw new Error(`OACP WRITE handshake failed: code ${result}`);
    }

    // Write chunks directly into the vendor ObjectData characteristic.
    // Max payload depends on MTU; we write in 20-byte chunks to be safe.
    const chunkSize = 20;
    for (let i = 0; i < bytes.length; i += chunkSize) {
      const chunk = bytes.slice(i, i + chunkSize);
      await this._writeChar(OtsUuids.ObjectData, chunk);
      // Firmware pacing: delay between chunks, but not after the last one
      if (i + chunkSize < bytes.length) {
        await sleep(250);
      }
    }
  }
}
