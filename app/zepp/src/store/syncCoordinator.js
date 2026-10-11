import { deviceStore } from './DeviceStore';
import { bleManager } from '../bluetooth/BleManager';

// Centralizes device-sync side effects so pages don't own BLE writes.
// TakeDevice = trust the board (state-only update). TakeLocal = keep the
// watch's settings and write them back to the board over BLE.
export function resolveConflict(deviceId, choice, remoteDevice, callbacks) {
  const cb = callbacks || {};
  deviceStore.resolveConflict(deviceId, choice, remoteDevice);

  if (choice === 'TakeLocal') {
    const local = deviceStore.getKnownDevices().find(d => d.id === deviceId);
    if (!local) {
      if (cb.onFailure) cb.onFailure(new Error('Device not found after resolve'));
      return;
    }
    writeDeviceToBoard(deviceId, local, cb);
  } else {
    if (cb.onSuccess) cb.onSuccess();
  }
}

// Writes the stored device to the board, then settles the store: the board baseline takes the
// written device, and the board settings take what the board reported back, because it ignores a
// language or a brightness that it does not know, and it clamps the screen-off delay. Pages save
// through this, not BleManager.
//
// A board in lockout does not accept a change (device.proto, lockout_enabled), so a device in
// lockout gets no write and the call fails at once with an error that has `lockout: true`.
export function writeDeviceToBoard(deviceId, device, callbacks) {
  const cb = callbacks || {};
  if (device && device.lockoutEnabled) {
    const err = new Error('The board is in lockout');
    err.lockout = true;
    if (cb.onFailure) cb.onFailure(err);
    return;
  }
  bleManager.writeDeviceConfiguration(
    deviceId,
    device,
    (written) => {
      deviceStore.applyWriteResult(deviceId, device, written);
      if (cb.onSuccess) cb.onSuccess(written);
    },
    (err) => { if (cb.onFailure) cb.onFailure(err); }
  );
}

// New-device confirmation has no side effect beyond persistence; exposed here
// so pages can route all sync operations through one module.
export function confirmNewDevice(pendingDevice, name) {
  deviceStore.confirmNewDevice(pendingDevice, name);
}
