import * as ble from '@zos/ble';
import { queryPermission, requestPermission } from '@zos/app';
import { isTransportTimeout, OtsClient } from './OtsClient';
import {
  BoardLanguage,
  BoardScreenBrightness,
  FireMode,
  MarkerBoltMode,
  SCREEN_OFF_DELAY_DEFAULT
} from '../protocol/OtsModelMapper';

export const MOCK_DEVICE_ID = "00000000-0000-0000-0000-000000001234";

// Native scan bound; the @zos/ble layer stops the scan when this elapses but
// emits no completion callback, so a companion client-side timer resets state.
const SCAN_TIMEOUT_MS = 60000;

export const BluetoothState = {
  Ready: 'Ready',
  Scanning: 'Scanning',
  Connecting: 'Connecting',
  Disabled: 'Disabled',
  NeedsPermission: 'NeedsPermission'
};

// The Bluetooth permission that app.json declares. The Zepp OS documentation gives no list of
// permission names, so the watch can report this name as unknown. An unknown name does not block
// the scan: only a clear "not authorized" does.
export const BLUETOOTH_PERMISSION = 'device:bluetooth';

// Result codes of @zos/app queryPermission (API_LEVEL 3.0), one for each permission.
const PERMISSION_NOT_AUTHORIZED = 0;
const PERMISSION_AUTHORIZED = 2;

// Return codes of @zos/app requestPermission (API_LEVEL 3.0).
const REQUEST_PENDING = 0;
const REQUEST_NOT_POSSIBLE = 1;
const REQUEST_AUTHORIZED = 2;

// Zepp OS has no documented function that tells if the Bluetooth radio of the watch is on. So a
// scan that does not start is the sign: mstStartScan returns false, or the stack calls fail. The
// documentation says that a call without authorization gives an error that names the permission,
// so a failure text that names a permission shows a permission problem, not a radio that is off.
const PERMISSION_ERROR_PATTERN = /permission|authori[sz]/i;

function isPermissionError(err) {
  if (err == null) return false;
  let text;
  try {
    text = typeof err === 'string' ? err : `${err.message || ''} ${JSON.stringify(err)}`;
  } catch (e) {
    text = String(err);
  }
  return PERMISSION_ERROR_PATTERN.test(text) || text.includes(BLUETOOTH_PERMISSION);
}

export const StepStatus = {
  PENDING: 'PENDING',
  IN_PROGRESS: 'IN_PROGRESS',
  COMPLETED: 'COMPLETED',
  FAILED: 'FAILED'
};

class BleManagerImpl {
  constructor() {
    this.state = BluetoothState.Ready;
    // True when the watch can make no permission request (requestPermission returned 1, is not
    // there, or failed). The user must then allow Bluetooth in the watch settings. A refusal in the
    // dialog does not set it: the next request asks again, or returns 1 when the watch cannot ask.
    this.permissionRequestRefused = false;
    // True while a permission request waits for its result, so a second tap asks no second time.
    this._permissionRequestPending = false;
    this.scanCallback = null;
    // State-change callback for the active scan, so the timeout/fail paths can
    // notify the page even though they aren't driven by a user action.
    this._scanStateCallback = null;
    this._scanTimeoutTimer = null;
    this.discoveredDevices = [];
    // A connection is identified by TWO keys that must never be conflated:
    //  - connectedAddress: the BLE link address (scan-provided MAC) used for
    //    every ble.mst* call.
    //  - connectedBoardId:  the board's protobuf UUID (Device.device_id) used as
    //    the DeviceStore key. Resolved from the decoded Device after the read.
    this.connectedAddress = null;
    this.connectedBoardId = null;
    // BLE address of a device whose mstConnect handshake is in flight (link not
    // yet up).
    this.connectingAddress = null;
    // One OtsClient per live connection; subscribed once via prepare() and
    // reused across the read and any writes, closed on disconnect.
    this._otsClient = null;
    // Bumped on every connect attempt and on disconnect. An in-flight connect
    // captures the value and, if it no longer matches when the handshake
    // completes, knows it was cancelled and releases its (late) link.
    this._connectGen = 0;
    // A board write is a sequence of GATT ops that shares the board's one OTS
    // selection, so two writes must never interleave. Each write chains onto
    // this queue and starts only when the write before it has settled.
    this._writeQueue = Promise.resolve();
  }

  isMock(deviceOrId) {
    const id = typeof deviceOrId === 'string' ? deviceOrId : (deviceOrId && deviceOrId.id);
    const flagged = deviceOrId && typeof deviceOrId === 'object' && deviceOrId.isMock;
    return !!(flagged || id === MOCK_DEVICE_ID);
  }

  // Resolves the BLE connect address for a device: the scan-provided address/MAC,
  // never the board's protobuf UUID (falling back to id only for the mock).
  _resolveConnectAddress(device) {
    if (this.isMock(device)) return MOCK_DEVICE_ID;
    return device.address || device.mac || device.id;
  }

  // Accepts either identifier: the board UUID (store lookups, e.g. device_config)
  // or the BLE address. Both map to the one live connection.
  isConnected(idOrAddress) {
    if (this.connectedAddress === null && this.connectedBoardId === null) return false;
    if (!idOrAddress) return false;
    return idOrAddress === this.connectedBoardId || idOrAddress === this.connectedAddress;
  }

  disconnect(onDone) {
    // Invalidate any in-flight connect so its late success drops the link.
    this._connectGen++;
    // Tear down whichever link exists: an established one, or one still being
    // established (cancel mid-handshake).
    const address = this.connectedAddress || this.connectingAddress;

    // Close the OtsClient before the link drops, so it sends no more GATT ops.
    // The subscriptions end with the link, so no CCCD unsubscribe is sent: on a
    // quick reconnect to the same address a late unsubscribe would turn off the
    // indications of the new link.
    const client = this._otsClient;
    this._otsClient = null;
    if (client) {
      try {
        const closing = client.close({ linkDropped: true });
        if (closing && typeof closing.catch === 'function') closing.catch(() => {});
      } catch (e) {
        console.log("Error closing OtsClient:", e);
      }
    }

    this.connectedAddress = null;
    this.connectedBoardId = null;
    this.connectingAddress = null;
    this.state = BluetoothState.Ready;
    if (address && address !== MOCK_DEVICE_ID && typeof ble.mstDisconnect === 'function') {
      try {
        ble.mstDisconnect({
          address: address,
          success: () => { if (onDone) onDone(); },
          fail: () => { if (onDone) onDone(); }
        });
      } catch (e) {
        console.log("Error calling mstDisconnect:", e);
        if (onDone) onDone();
      }
    } else if (onDone) {
      onDone();
    }
  }

  _clearScanTimeout() {
    if (this._scanTimeoutTimer) {
      clearTimeout(this._scanTimeoutTimer);
      this._scanTimeoutTimer = null;
    }
  }

  // Ends a scan that wasn't stopped by the user (native timeout elapsed, or the
  // scan failed to start): set [nextState] and notify so the UI doesn't strand
  // in "Scanning". A scan that could not start gives Disabled or NeedsPermission.
  _endScan(nextState = BluetoothState.Ready) {
    if (this.state !== BluetoothState.Scanning) return;
    this._clearScanTimeout();
    this.state = nextState;
    this.scanCallback = null;
    const cb = this._scanStateCallback;
    this._scanStateCallback = null;
    try {
      if (typeof ble.mstStopScan === 'function') ble.mstStopScan();
    } catch (e) {
      console.log("Error calling mstStopScan:", e);
    }
    if (cb) cb(this.state);
  }

  // Whether the Bluetooth permission is clearly not authorized. A watch without the @zos/app
  // permission functions, a name the watch does not know, and an error all give false, so the
  // scan goes on and its own result decides.
  _permissionDenied() {
    if (typeof queryPermission !== 'function') return false;
    try {
      const result = queryPermission({ permissions: [BLUETOOTH_PERMISSION] });
      const code = Array.isArray(result) ? result[0] : undefined;
      if (code === PERMISSION_AUTHORIZED) this.permissionRequestRefused = false;
      return code === PERMISSION_NOT_AUTHORIZED;
    } catch (e) {
      console.log("Error calling queryPermission:", e);
      return false;
    }
  }

  // Whether the mock devices are on (app.globalData.mockDevicesEnabled). They need no radio.
  _mockDevicesEnabled() {
    const app = getApp();
    return !!(app && app.globalData && app.globalData.mockDevicesEnabled);
  }

  // Checks the Bluetooth permission and returns the state a page shows before a scan: Ready,
  // Disabled or NeedsPermission. It does not start or stop a scan or a connection. Disabled stays
  // until the next scan starts, because only a scan can find that the radio is off. With the mock
  // devices on, a missing permission does not block the scan, because the mock board needs no radio.
  refreshAvailability() {
    if (this._isBusy()) return this.state;
    if (!this._mockDevicesEnabled() && this._permissionDenied()) {
      this.state = BluetoothState.NeedsPermission;
    } else if (this.state === BluetoothState.NeedsPermission) {
      this.state = BluetoothState.Ready;
    }
    return this.state;
  }

  // Whether a scan runs, or a link is up or on the way up. A permission result must not change the
  // state then, because the scan or the link owns it.
  _isBusy() {
    return this.state === BluetoothState.Scanning ||
      this.state === BluetoothState.Connecting ||
      this.connectedAddress !== null;
  }

  // Asks the user for the Bluetooth permission. [onStateChanged] gets Ready when the permission is
  // authorized, or NeedsPermission when it is not. When no request is possible,
  // permissionRequestRefused becomes true: the user must allow Bluetooth in the watch settings.
  // A call while an earlier request waits for its result does nothing.
  requestBluetoothPermission(onStateChanged) {
    if (this._permissionRequestPending) return;
    this._permissionRequestPending = true;
    // A watch can return a code and also call the callback, so only the first result counts.
    let settled = false;
    const settle = (granted, requestPossible = true) => {
      if (settled) return;
      settled = true;
      this._permissionRequestPending = false;
      this.permissionRequestRefused = !granted && !requestPossible;
      if (this._isBusy()) return;
      this.state = granted ? BluetoothState.Ready : BluetoothState.NeedsPermission;
      if (onStateChanged) onStateChanged(this.state);
    };
    if (typeof requestPermission !== 'function') {
      settle(false, false);
      return;
    }
    try {
      const code = requestPermission({
        permissions: [BLUETOOTH_PERMISSION],
        callback: (result) => {
          settle(Array.isArray(result) && result[0] === PERMISSION_AUTHORIZED);
        }
      });
      if (code === REQUEST_AUTHORIZED) {
        settle(true);
      } else if (code === REQUEST_NOT_POSSIBLE) {
        settle(false, false);
      } else if (code !== REQUEST_PENDING) {
        // No callback can follow a code that the documentation does not give, so end the request
        // here. Otherwise each later tap does nothing.
        console.log("Unexpected requestPermission result:", code);
        settle(false);
      }
    } catch (e) {
      console.log("Error calling requestPermission:", e);
      settle(false, false);
    }
  }

  startScanning(onDeviceFound, onStateChanged) {
    if (this.state === BluetoothState.Scanning) return;

    const mockDevicesEnabled = this._mockDevicesEnabled();
    // With the mock devices on, a missing permission stops only the radio scan, so the mock board
    // still shows. This agrees with a radio scan that does not start (see below).
    const permissionDenied = this._permissionDenied();
    if (permissionDenied && !mockDevicesEnabled) {
      this.state = BluetoothState.NeedsPermission;
      if (onStateChanged) onStateChanged(this.state);
      return;
    }

    this.state = BluetoothState.Scanning;
    this.scanCallback = onDeviceFound;
    this._scanStateCallback = onStateChanged;
    this.discoveredDevices = [];
    if (onStateChanged) onStateChanged(this.state);

    console.log("Starting BLE Scan...");

    // Insert mock device if enabled
    if (mockDevicesEnabled) {
      setTimeout(() => {
        const mockDev = {
          id: MOCK_DEVICE_ID,
          name: "Snapshot PB",
          boardType: "Cocker",
          fwVersion: null,
          isMock: true
        };
        this.discoveredDevices.push(mockDev);
        if (this.scanCallback) this.scanCallback([...this.discoveredDevices]);
      }, 500);
    }

    if (permissionDenied) {
      console.log("No Bluetooth permission; only the mock devices show");
      this._scanTimeoutTimer = setTimeout(() => {
        this._scanTimeoutTimer = null;
        this._endScan();
      }, SCAN_TIMEOUT_MS);
      return;
    }

    try {
      if (typeof ble.mstStartScan === 'function') {
        // Companion client-side timer: the native `timeout` stops the scan with
        // no completion callback, so flip state back to Ready when it elapses.
        this._scanTimeoutTimer = setTimeout(() => {
          this._scanTimeoutTimer = null;
          console.log("BLE scan timed out; resetting to Ready");
          this._endScan();
        }, SCAN_TIMEOUT_MS);

        const started = ble.mstStartScan({
          timeout: SCAN_TIMEOUT_MS,
          fail: (err) => {
            console.log("BLE scan failed to start:", err);
            this._endScan(isPermissionError(err)
              ? BluetoothState.NeedsPermission
              : BluetoothState.Disabled);
          }
        }, (res) => {
          if (res && res.name === "Snapshot PB") {
            // Log only a matched board. A scan reports every advertisement near
            // the watch many times a second, and a log line for each one is load
            // on the watch with no use.
            console.log("Discovered device over BLE:", JSON.stringify(res));
            // A scan that finds a board has the permission, also on a watch that reports the
            // permission name as unknown.
            this.permissionRequestRefused = false;
            const devId = res.address || res.dev_id;
            if (devId && !this.discoveredDevices.some(d => d.id === devId)) {
              // The watch does not parse the manufacturer/service advertisement payload (only the
              // Android seam does), so there is no real firmware version to show here. Report it as
              // unknown rather than inventing a number the user might act on.
              const boardType = "Snapshot PB";
              const fwVersion = null;

              const newDev = {
                id: devId,
                name: res.name,
                boardType,
                fwVersion,
                rssi: res.rssi
              };
              this.discoveredDevices.push(newDev);
              if (this.scanCallback) this.scanCallback([...this.discoveredDevices]);
            }
          }
        });
        // The documented mstStartScan returns false when the scan cannot start. Only a strict
        // false counts: a stack that returns nothing reports a failure through fail.
        //
        // CAUTION: the documented signature is mstStartScan(callback, filter), but this call gives
        // (options, callback). A watch that follows the documentation can return false for each
        // call, and the list then shows "Bluetooth is off" with Bluetooth on. Examine the return
        // value on hardware before this path is trusted.
        //
        // With the mock devices on, the scan stays on, so the mock board still shows on a
        // simulator that has no BLE Central radio.
        if (started === false) {
          if (mockDevicesEnabled) {
            console.log("BLE scan did not start; the mock devices stay available");
          } else {
            console.log("BLE scan did not start; Bluetooth is off or not available");
            this._endScan(BluetoothState.Disabled);
          }
        }
      } else {
        // No scan API: do not leave the list on "Stop Scanning" with nothing to stop.
        this._endScan();
      }
    } catch (e) {
      // A throw shows a call shape that this watch does not support, not a radio that is off, so
      // the list goes back to Ready and not to Disabled.
      console.log("Error calling mstStartScan:", e);
      this._endScan();
    }
  }

  stopScanning(onStateChanged) {
    if (this.state !== BluetoothState.Scanning) return;
    this._clearScanTimeout();
    this.state = BluetoothState.Ready;
    this.scanCallback = null;
    this._scanStateCallback = null;
    if (onStateChanged) onStateChanged(this.state);

    console.log("Stopping BLE Scan...");

    try {
      if (typeof ble.mstStopScan === 'function') {
        ble.mstStopScan();
      }
    } catch (e) {
      console.log("Error calling mstStopScan:", e);
    }
  }

  connect(device, onProgress, onSuccess, onFailure) {
    console.log("Connecting to device:", device.id);
    this.state = BluetoothState.Connecting;

    if (this.isMock(device)) {
      this._runMockConnect(device, onProgress, onSuccess);
      return;
    }

    this._runRealConnect(device, onProgress, onSuccess, onFailure);
  }

  _runMockConnect(device, onProgress, onSuccess) {
    const gen = ++this._connectGen;
    const progress = {
      step1: StepStatus.IN_PROGRESS,
      step2: StepStatus.PENDING,
      step3: StepStatus.PENDING,
      step4: StepStatus.PENDING
    };
    onProgress({ ...progress });

    // Step 1: Connect
    setTimeout(() => {
      progress.step1 = StepStatus.COMPLETED;
      progress.step2 = StepStatus.IN_PROGRESS;
      onProgress({ ...progress });

      // Step 2: Discover Services
      setTimeout(() => {
        progress.step2 = StepStatus.COMPLETED;
        progress.step3 = StepStatus.IN_PROGRESS;
        onProgress({ ...progress });

        // Step 3: Device Info
        setTimeout(() => {
          progress.step3 = StepStatus.COMPLETED;
          progress.step4 = StepStatus.IN_PROGRESS;
          onProgress({ ...progress });

          // Step 4: Profiles
          setTimeout(() => {
            if (gen !== this._connectGen) return; // cancelled while connecting
            progress.step4 = StepStatus.COMPLETED;
            onProgress({ ...progress });

            // Generate mock device
            const mockDevice = {
              id: MOCK_DEVICE_ID,
              name: "Snapshot PB E2E",
              mac: "00:00:00:00:12:34",
              lastSeen: new Date().toISOString().split('T')[0],
              knownProfiles: [
                {
                  id: "00000000-0000-0000-0000-000000000001",
                  name: "Semi Auto",
                  fireMode: "SEMI",
                  fireRateCapTenths: 150,
                  eyeMode: "OFF",
                  eyeThreshold: 50,
                  eyeDebounce: 10,
                  searSolenoidDwell: 60,
                  searSolenoidDelay: 60,
                  cockingSolenoidDelay: 20,
                  cockingSolenoidDwellOpen: 30,
                  cockingSolenoidDwellClosed: 40,
                  rampingCustomMultiplier: 15,
                  rampingCustomTriggerPulls: 5,
                  rampingCustomROFMinimum: 50,
                  triggerDebounceOn: 5,
                  triggerDebounceOff: 5,
                  cockingSolenoidTimeout: 5000
                },
                {
                  id: "00000000-0000-0000-0000-000000000002",
                  name: "Ramp Custom",
                  fireMode: FireMode.CUSTOM_RAMPING,
                  fireRateCapTenths: 150,
                  eyeMode: "REFLECTIVE",
                  eyeThreshold: 60,
                  eyeDebounce: 8,
                  searSolenoidDwell: 55,
                  searSolenoidDelay: 60,
                  cockingSolenoidDelay: 15,
                  cockingSolenoidDwellOpen: 25,
                  cockingSolenoidDwellClosed: 35,
                  rampingCustomMultiplier: 20,
                  rampingCustomTriggerPulls: 4,
                  rampingCustomROFMinimum: 60,
                  triggerDebounceOn: 4,
                  triggerDebounceOff: 4,
                  cockingSolenoidTimeout: 5000
                }
              ],
              activeProfileId: "00000000-0000-0000-0000-000000000001",
              bootText: "SnapshotPB Mock",
              language: BoardLanguage.EN,
              screenBrightness: BoardScreenBrightness.DEFAULT,
              screenOffDelay: SCREEN_OFF_DELAY_DEFAULT,
              boltMode: MarkerBoltMode.CLOSED,
              lockoutEnabled: false,
              boardModel: "AUTOCOCKER",
              supportedProfileCount: 2
            };

            this.state = BluetoothState.Ready;
            this.connectedAddress = MOCK_DEVICE_ID;
            this.connectedBoardId = MOCK_DEVICE_ID;
            onSuccess(mockDevice);
          }, 500);
        }, 500);
      }, 500);
    }, 500);
  }

  _runRealConnect(device, onProgress, onSuccess, onFailure) {
    const gen = ++this._connectGen;
    // Defensive: a new connection must not leak a prior client's subscriptions
    // (normal flows disconnect first, which already closes it).
    if (this._otsClient) {
      const stale = this._otsClient;
      this._otsClient = null;
      try {
        // The new connect replaces this link, so send no unsubscribes (see disconnect).
        const closing = stale.close({ linkDropped: true });
        if (closing && typeof closing.catch === 'function') closing.catch(() => {});
      } catch (e) {
        console.log("Error closing stale OtsClient:", e);
      }
    }
    const address = this._resolveConnectAddress(device);
    this.connectingAddress = address;
    const progress = {
      step1: StepStatus.IN_PROGRESS,
      step2: StepStatus.PENDING,
      step3: StepStatus.PENDING,
      step4: StepStatus.PENDING
    };
    onProgress({ ...progress });

    try {
      if (typeof ble.mstConnect !== 'function') {
        throw new Error("BLE Master API not supported on this device.");
      }

      // Start connection
      ble.mstConnect({
        address: address,
        success: () => {
          this.connectingAddress = null;
          if (gen !== this._connectGen) {
            // Cancelled while the handshake was in flight — release the link
            // that just came up instead of driving the rest of the flow.
            console.log("Connect aborted; releasing late link:", address);
            if (typeof ble.mstDisconnect === 'function') {
              try {
                ble.mstDisconnect({ address: address });
              } catch (e) {
                console.log("Error calling mstDisconnect:", e);
              }
            }
            return;
          }
          console.log("BLE connected successfully to:", address);
          this.connectedAddress = address;
          progress.step1 = StepStatus.COMPLETED;
          progress.step2 = StepStatus.IN_PROGRESS;
          onProgress({ ...progress });

          // Discover Services & Build Profile
          this._buildGattProfile(address, progress, onProgress, onSuccess, onFailure);
        },
        fail: (err) => {
          if (gen !== this._connectGen) {
            // A cancelled attempt failed late; a newer attempt owns the state now.
            console.log("Connect aborted; ignoring late failure:", address);
            return;
          }
          this.connectingAddress = null;
          console.log("BLE connection failed:", err);
          this.connectedAddress = null;
          progress.step1 = StepStatus.FAILED;
          onProgress({ ...progress });
          this.state = BluetoothState.Ready;
          onFailure(new Error("Connection failed"));
        }
      });
    } catch (e) {
      this.connectingAddress = null;
      console.log("Error in _runRealConnect:", e);
      this.connectedAddress = null;
      progress.step1 = StepStatus.FAILED;
      onProgress({ ...progress });
      this.state = BluetoothState.Ready;
      onFailure(e);
    }
  }

  _buildGattProfile(address, progress, onProgress, onSuccess, onFailure) {
    const serviceUuid = "1825";

    try {
      ble.mstBuildProfile({
        address: address,
        services: [
          {
            uuid: serviceUuid,
            characteristics: [
              { uuid: "2AC5", descriptors: [{ uuid: "2902" }] }, // OACP
              { uuid: "2AC6", descriptors: [{ uuid: "2902" }] }, // OLCP
              { uuid: "2ABE" }, // Object Name
              { uuid: "2ABF" }, // Object Type
              { uuid: "2AC0" }, // Object Size
              { uuid: "2AC3" }, // Object ID
              { uuid: "2AC4" }, // Object Properties
              { uuid: "2ABD" }, // Feature
              { uuid: "00000002-5350-4221-0000-5350425f4f54", descriptors: [{ uuid: "2902" }] } // ObjectData (Notify)
            ]
          }
        ],
        success: () => {
          console.log("GATT Profile built successfully");
          progress.step2 = StepStatus.COMPLETED;
          progress.step3 = StepStatus.IN_PROGRESS;
          onProgress({ ...progress });

          // Initialize OTS client and perform read
          this._readOtsData(address, progress, onProgress, onSuccess, onFailure);
        },
        fail: (err) => {
          console.log("GATT Profile build failed:", err);
          // Link was already established by mstConnect — actually release it
          // (not just forget the id) so the board is free to reconnect.
          this.disconnect();
          progress.step2 = StepStatus.FAILED;
          onProgress({ ...progress });
          this.state = BluetoothState.Ready;
          onFailure(new Error("Service discovery failed"));
        }
      });
    } catch (e) {
      console.log("Error in _buildGattProfile:", e);
      // Link was already established by mstConnect — actually release it.
      this.disconnect();
      progress.step2 = StepStatus.FAILED;
      onProgress({ ...progress });
      this.state = BluetoothState.Ready;
      onFailure(e);
    }
  }

  _readOtsData(address, progress, onProgress, onSuccess, onFailure) {
    const gen = this._connectGen;
    const client = new OtsClient(address);
    this._otsClient = client;

    // Subscribe OACP/OLCP/ObjectData ONCE, then reuse the client across the read
    // and any later write. Kept on until disconnect() calls close().
    client.prepare()
      .then(() => {
        if (gen !== this._connectGen) return; // cancelled while preparing

        client.readDeviceAndProfiles((deviceData) => {
          if (gen !== this._connectGen) return;
          // Success
          progress.step3 = StepStatus.COMPLETED;
          progress.step4 = StepStatus.COMPLETED;
          onProgress({ ...progress });
          this.state = BluetoothState.Ready;
          // Record the board UUID resolved from the decoded Device so store-key
          // isConnected() checks (device_config uses device.id) match this link.
          this.connectedBoardId = deviceData.id;
          onSuccess(deviceData);
        }, (err) => {
          if (gen !== this._connectGen) return;
          // Failure
          console.log("OTS read failed:", err);
          if (progress.step3 === StepStatus.IN_PROGRESS) {
            progress.step3 = StepStatus.FAILED;
          } else {
            progress.step4 = StepStatus.FAILED;
          }
          onProgress({ ...progress });
          this.state = BluetoothState.Ready;
          // The read failed after the link was established — tear it down (which
          // also closes the OtsClient) so the board is free to reconnect.
          this.disconnect();
          onFailure(err);
        }, (phase) => {
          if (phase === 'deviceRead') {
            progress.step3 = StepStatus.COMPLETED;
            progress.step4 = StepStatus.IN_PROGRESS;
            onProgress({ ...progress });
          }
        });
      })
      .catch((err) => {
        if (gen !== this._connectGen) return;
        console.log("OTS prepare failed:", err);
        progress.step3 = StepStatus.FAILED;
        onProgress({ ...progress });
        this.state = BluetoothState.Ready;
        this.disconnect();
        onFailure(err);
      });
  }

  writeDeviceConfiguration(deviceId, domainDevice, onSuccess, onFailure) {
    console.log("Writing device config for:", deviceId);
    if (this.isMock(deviceId)) {
      // Mock write: just succeed immediately after a small delay. The mock board knows every
      // language and brightness, so it reports back the values that were sent.
      setTimeout(() => {
        console.log("Mock write configuration complete.");
        onSuccess({
          language: domainDevice.language || null,
          screenBrightness: domainDevice.screenBrightness || null,
          screenOffDelay: domainDevice.screenOffDelay || null,
          boltMode: domainDevice.boltMode || null
        });
      }, 500);
      return;
    }

    // Reuse the live connection's prepared OtsClient (subscribed once in
    // prepare()); writes only happen while connected.
    const client = this._otsClient;
    if (!client || this.connectedAddress === null) {
      onFailure(new Error("Not connected to device"));
      return;
    }
    // One write at a time: a second write waits until the first settles. Two
    // pages can each start a save (profile_edit, then device_config after back),
    // and interleaved selections could put Profiles bytes into device_config.
    const run = () => new Promise((settle) => {
      // The link can drop while this write waits in the queue.
      if (this._otsClient !== client || client.closed) {
        settle();
        onFailure(new Error("Not connected to device"));
        return;
      }
      client.writeDeviceConfiguration(domainDevice, (written) => {
        settle();
        onSuccess(written);
      }, (err) => {
        settle();
        onFailure(this._onWriteFailure(client, err));
      });
    });
    this._writeQueue = this._writeQueue.then(run, run);
  }

  // The app gets no event when the board goes out of range. A transport timeout
  // during a write is the sign of a lost link, so release the link: the page then
  // shows the device as disconnected, and the next connect starts clean instead
  // of first needing a manual Disconnect. The returned error has linkLost set.
  _onWriteFailure(client, err) {
    const error = err instanceof Error ? err : new Error(String(err));
    if (isTransportTimeout(error) && this._otsClient === client) {
      console.log("Board write timed out; treating the link as lost:", error.message);
      this.disconnect();
      error.linkLost = true;
    }
    return error;
  }
}

export const bleManager = new BleManagerImpl();
