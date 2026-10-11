// Stand-in for @zos/ble — the legacy BLE Master (Central) API. Every mst*
// function forwards to whichever VirtualRadio is installed; the radio owns all
// behaviour, this module owns only the API shape.
//
// The arrived/notification callback lists are deliberately NEVER cleared:
// OtsClient registers its listeners once, at module load, and a test that
// cleared them would silently break every later flow in the same file. Only the
// radio behind them is swapped between tests.

const arrivedCallbacks = [];
const notificationCallbacks = [];

let radio = null;

export function installRadio(nextRadio) {
  // Stop the outgoing radio first. Its pending timers would otherwise keep
  // delivering into the callback lists below — which are never cleared — and
  // bleed stale GATT events into the next test in the same file.
  if (radio && typeof radio.detach === 'function') radio.detach();
  radio = nextRadio;
  if (radio) {
    radio.attach({
      deliverValue: res => arrivedCallbacks.forEach(cb => cb(res)),
      deliverNotification: res => notificationCallbacks.forEach(cb => cb(res))
    });
  }
}

export function currentRadio() {
  return radio;
}

export const bleRecorder = {
  /** Registered app listeners — a count, never a reset (see above). */
  listenerCounts() {
    return { arrived: arrivedCallbacks.length, notification: notificationCallbacks.length };
  }
};

// --- Scanning ---------------------------------------------------------------

// With no radio installed the stack cannot succeed OR fail silently: a caller
// waiting on success/fail would hang to the test timeout with a useless error.
// A real stack reports the failure, so the mock does too.
function noRadio(options) {
  const fail = options && options.fail;
  if (typeof fail === 'function') setTimeout(() => fail('no radio installed'), 0);
}

// Returns what the radio returns: the documented mstStartScan returns false
// when the scan cannot start.
export function mstStartScan(options, callback) {
  if (!radio) return undefined;
  return radio.startScan(options || {}, callback);
}

export function mstStopScan() {
  if (!radio) return;
  radio.stopScan();
}

// --- Connection -------------------------------------------------------------

export function mstConnect(options) {
  if (!radio) return noRadio(options);
  radio.connect(options || {});
}

export function mstDisconnect(options) {
  if (!radio) return noRadio(options);
  radio.disconnect(options || {});
}

export function mstBuildProfile(options) {
  if (!radio) return noRadio(options);
  radio.buildProfile(options || {});
}

// --- GATT -------------------------------------------------------------------

export function mstReadCharacteristic(options) {
  if (!radio) return noRadio(options);
  radio.readCharacteristic(options || {});
}

export function mstWriteCharacteristic(options) {
  if (!radio) return noRadio(options);
  radio.writeCharacteristic(options || {});
}

export function mstPrepareNotificationChar(options) {
  if (!radio) return noRadio(options);
  radio.prepareNotification(options || {}, 'notify');
}

export function mstPrepareIndicateChar(options) {
  if (!radio) return noRadio(options);
  radio.prepareNotification(options || {}, 'indicate');
}

// --- Events -----------------------------------------------------------------

export function mstOnCharaValueArrived(callback) {
  arrivedCallbacks.push(callback);
}

export function mstOnCharaNotification(callback) {
  notificationCallbacks.push(callback);
}
