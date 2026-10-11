// The device list when the watch cannot scan: Bluetooth is off, or the app has
// no Bluetooth permission.
//
// Zepp OS has no documented function that tells if the radio is on, so
// BleManager finds Disabled from a scan that does not start: mstStartScan
// returns false, or the stack calls fail. The permission comes from the
// @zos/app queryPermission and requestPermission functions (API_LEVEL 3.0).
// Both paths are verified against the mocks only (see .test/README.md).

import { afterEach, describe, expect, test } from 'vitest';
import { startHarness } from '../harness';
import { LABELS, PAGES } from '../harness/flows';
import { realSetTimeout } from '../mocks/globals';

const BLUETOOTH_PERMISSION = 'device:bluetooth';
const NOT_AUTHORIZED = 0;
const UNKNOWN_PERMISSION = 1;
const AUTHORIZED = 2;

// Waits on real time, so every pending app timer on the compressed clock runs.
function settle(ms = 50) {
  return new Promise(resolve => realSetTimeout(resolve, ms));
}

describe('Bluetooth state on the device list', () => {
  let h;

  afterEach(() => {
    if (h) h.teardown();
    h = null;
  });

  test('a scan that returns false shows that Bluetooth is off, and Try Again scans', async () => {
    h = await startHarness({ faults: { scan: 'refuse' } });
    h.open(PAGES.deviceList);
    h.click(LABELS.scan);

    await h.waitForText(LABELS.bluetoothOff);
    expect(h.ble.state).toBe(h.BluetoothState.Disabled);
    expect(h.hasText(LABELS.scan)).toBe(false);
    expect(h.hasText(LABELS.stopScan)).toBe(false);

    // The user turns Bluetooth on.
    delete h.faults.scan;
    h.click(LABELS.tryAgain);
    await h.waitForText(LABELS.discovered);
    expect(h.hasText(LABELS.bluetoothOff)).toBe(false);
  });

  test('a scan whose fail callback runs shows that Bluetooth is off', async () => {
    h = await startHarness({ faults: { scan: 'fail', scanError: 'ble not available' } });
    h.open(PAGES.deviceList);
    h.click(LABELS.scan);

    await h.waitForText(LABELS.bluetoothOff);
    expect(h.ble.state).toBe(h.BluetoothState.Disabled);
    expect(h.hasText(LABELS.tryAgain)).toBe(true);
  });

  test('a scan failure that names a permission shows the permission request', async () => {
    h = await startHarness({
      faults: { scan: 'fail', scanError: `permission ${BLUETOOTH_PERMISSION} is not authorized` }
    });
    h.open(PAGES.deviceList);
    h.click(LABELS.scan);

    await h.waitForText(LABELS.noPermission);
    expect(h.ble.state).toBe(h.BluetoothState.NeedsPermission);
    expect(h.hasText(LABELS.allowBluetooth)).toBe(true);
    expect(h.hasText(LABELS.bluetoothOff)).toBe(false);
  });

  test('a missing permission shows when the page opens, and Allow Bluetooth asks and scans', async () => {
    h = await startHarness();
    h.app.setPermission(BLUETOOTH_PERMISSION, NOT_AUTHORIZED);
    h.open(PAGES.deviceList);

    expect(h.hasText(LABELS.noPermission)).toBe(true);
    expect(h.hasText(LABELS.scan)).toBe(false);

    h.click(LABELS.allowBluetooth);
    await h.waitForText(LABELS.discovered);
    expect(h.app.requests()).toEqual([[BLUETOOTH_PERMISSION]]);
    expect(h.ble.state).toBe(h.BluetoothState.Scanning);
  });

  test('a scan does not start while the permission is not authorized', async () => {
    h = await startHarness();
    h.open(PAGES.deviceList);
    h.app.setPermission(BLUETOOTH_PERMISSION, NOT_AUTHORIZED);
    h.click(LABELS.scan);

    await h.waitForText(LABELS.allowBluetooth);
    expect(h.radio.callsTo('mstStartScan')).toHaveLength(0);
    expect(h.ble.state).toBe(h.BluetoothState.NeedsPermission);
  });

  test('a request the watch cannot make sends the user to the watch settings', async () => {
    h = await startHarness();
    h.app.setPermission(BLUETOOTH_PERMISSION, NOT_AUTHORIZED);
    h.app.setRequestOutcome(1);
    h.open(PAGES.deviceList);
    h.click(LABELS.allowBluetooth);

    await h.waitForText(LABELS.permissionInSettings);
    expect(h.hasText(LABELS.tryAgain)).toBe(true);
    expect(h.hasText(LABELS.allowBluetooth)).toBe(false);

    // The user allows Bluetooth in the watch settings.
    h.app.setPermission(BLUETOOTH_PERMISSION, AUTHORIZED);
    h.click(LABELS.tryAgain);
    await h.waitForText(LABELS.discovered);
  });

  test('a user who refuses the request can ask again, until the watch cannot ask', async () => {
    h = await startHarness();
    h.app.setPermission(BLUETOOTH_PERMISSION, NOT_AUTHORIZED);
    h.app.setRequestOutcome(0, false);
    h.open(PAGES.deviceList);
    h.click(LABELS.allowBluetooth);

    await h.waitFor(() => h.app.requests().length === 1 && h.hasText(LABELS.allowBluetooth) &&
      !h.ble._permissionRequestPending);
    await settle();
    expect(h.ble.state).toBe(h.BluetoothState.NeedsPermission);
    expect(h.hasText(LABELS.noPermission)).toBe(true);
    expect(h.hasText(LABELS.permissionInSettings)).toBe(false);
    expect(h.radio.callsTo('mstStartScan')).toHaveLength(0);

    // The watch will not ask a second time: requestPermission returns 1.
    h.app.setRequestOutcome(1);
    h.click(LABELS.allowBluetooth);
    await h.waitForText(LABELS.permissionInSettings);
    expect(h.app.requests()).toHaveLength(2);
    expect(h.hasText(LABELS.tryAgain)).toBe(true);
  });

  test('a second tap while the request waits asks only once', async () => {
    h = await startHarness();
    h.app.setPermission(BLUETOOTH_PERMISSION, NOT_AUTHORIZED);
    // The dialog stays open: the callback does not run.
    h.app.setRequestOutcome(0, true, false);
    h.open(PAGES.deviceList);
    h.click(LABELS.allowBluetooth);
    h.click(LABELS.allowBluetooth);

    await settle();
    expect(h.app.requests()).toHaveLength(1);
    expect(h.radio.callsTo('mstStartScan')).toHaveLength(0);
  });

  test('the mock board shows when the permission is not authorized', async () => {
    h = await startHarness();
    h.app.setPermission(BLUETOOTH_PERMISSION, NOT_AUTHORIZED);
    getApp().globalData.mockDevicesEnabled = true;
    h.open(PAGES.deviceList);
    expect(h.hasText(LABELS.noPermission)).toBe(false);

    h.click(LABELS.scan);
    await h.waitForText('[#1234]');
    expect(h.ble.state).toBe(h.BluetoothState.Scanning);
    // Without the permission, the app does not start a radio scan.
    expect(h.radio.callsTo('mstStartScan')).toHaveLength(0);
  });

  test('a Disabled list shows Scan again when BleManager is Ready on resume', async () => {
    h = await startHarness({ faults: { scan: 'refuse' } });
    const page = h.open(PAGES.deviceList);
    h.click(LABELS.scan);
    await h.waitForText(LABELS.bluetoothOff);

    // A connect or a disconnect on another page sets BleManager to Ready.
    delete h.faults.scan;
    h.ble.disconnect();
    page.onResume();

    expect(h.hasText(LABELS.bluetoothOff)).toBe(false);
    expect(h.hasText(LABELS.scan)).toBe(true);
  });

  test('a permission allowed in the watch settings clears the message on resume', async () => {
    h = await startHarness();
    h.app.setPermission(BLUETOOTH_PERMISSION, NOT_AUTHORIZED);
    const page = h.open(PAGES.deviceList);
    expect(h.hasText(LABELS.noPermission)).toBe(true);

    h.app.setPermission(BLUETOOTH_PERMISSION, AUTHORIZED);
    page.onResume();

    expect(h.hasText(LABELS.noPermission)).toBe(false);
    expect(h.hasText(LABELS.scan)).toBe(true);
  });

  test('a permission name the watch does not know does not block the scan', async () => {
    h = await startHarness();
    h.app.setPermission(BLUETOOTH_PERMISSION, UNKNOWN_PERMISSION);
    h.open(PAGES.deviceList);

    expect(h.hasText(LABELS.noPermission)).toBe(false);
    h.click(LABELS.scan);
    await h.waitForText(LABELS.discovered);
  });

  test('a request that returns authorized at once scans', async () => {
    h = await startHarness();
    h.app.setPermission(BLUETOOTH_PERMISSION, NOT_AUTHORIZED);
    h.app.setRequestOutcome(2);
    h.open(PAGES.deviceList);
    h.click(LABELS.allowBluetooth);

    await h.waitForText(LABELS.discovered);
    expect(h.radio.callsTo('mstStartScan')).toHaveLength(1);
  });

  test('a watch that returns authorized and also calls back starts one scan only', async () => {
    h = await startHarness();
    h.app.setPermission(BLUETOOTH_PERMISSION, NOT_AUTHORIZED);
    h.app.setRequestOutcome(2, true, true);
    h.open(PAGES.deviceList);
    h.click(LABELS.allowBluetooth);

    await h.waitForText(LABELS.discovered);
    await settle();
    expect(h.radio.callsTo('mstStartScan')).toHaveLength(1);
    expect(h.ble.state).toBe(h.BluetoothState.Scanning);
  });

  test('a permission result after the page closes draws nothing and does not scan', async () => {
    h = await startHarness();
    h.app.setPermission(BLUETOOTH_PERMISSION, NOT_AUTHORIZED);
    h.open(PAGES.deviceList);
    h.click(LABELS.allowBluetooth);
    h.back();

    await settle();
    expect(h.app.requests()).toHaveLength(1);
    expect(h.radio.callsTo('mstStartScan')).toHaveLength(0);
    expect(h.texts()).toEqual([]);
  });

  test('the mock board still shows when the scan does not start', async () => {
    h = await startHarness({ faults: { scan: 'refuse' } });
    getApp().globalData.mockDevicesEnabled = true;
    h.open(PAGES.deviceList);
    h.click(LABELS.scan);

    await h.waitForText('[#1234]');
    expect(h.ble.state).toBe(h.BluetoothState.Scanning);
    expect(h.hasText(LABELS.bluetoothOff)).toBe(false);
  });
});
