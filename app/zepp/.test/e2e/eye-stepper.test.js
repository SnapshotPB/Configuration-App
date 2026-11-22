// Page-level tests for the live eye line on the EYE_THRESHOLD stepper.

import { afterEach, describe, expect, test } from 'vitest';
import { startHarness } from '../harness';
import { LABELS, openProfileField, registerKnownDevice } from '../harness/flows';
import { EYE_UUID, normalizeUuid } from '../virtual-board/VirtualBoard';
import { MOCK_DEVICE_ID } from '../../src/bluetooth/BleManager';

const LIVE_EYE_PATTERN = /^Eye \d+\.\d%/;

function eyeSubCalls(radio) {
  return radio
    .callsTo('mstPrepareNotificationChar')
    .filter(c => normalizeUuid(c.uuid) === EYE_UUID.STATUS);
}

function liveEyeText(h) {
  return h.texts().find(t => LIVE_EYE_PATTERN.test(t)) || null;
}

describe('EYE_THRESHOLD stepper live eye line', () => {
  let h;

  afterEach(() => {
    if (h) h.teardown();
    h = null;
  });

  test('shows the live eye level and updates when the board level changes', async () => {
    h = await startHarness();
    const device = await registerKnownDevice(h);
    getApp().globalData.selectedDevice = device;

    openProfileField(h, device.knownProfiles[0].id, 'EYE_THRESHOLD');
    await h.waitForText('Eye 50.0% - Empty');

    h.board.setEyeStatus({ levelRaw: 3000, fullScaleRaw: 4095, state: 1 });
    await h.waitForText('Eye 73.3% - Ball');
  });

  test('steps of the threshold change the ball result at once', async () => {
    h = await startHarness();
    const device = await registerKnownDevice(h);
    getApp().globalData.selectedDevice = device;

    openProfileField(h, device.knownProfiles[0].id, 'EYE_THRESHOLD');
    await h.waitForText('Eye 50.0% - Empty');

    // Default threshold is 60%. Three coarse minus steps (-5) bring it to 45%,
    // which puts raw level 2047 above the raw threshold.
    h.click('-5');
    h.click('-5');
    h.click('-5');

    expect(h.hasText('Eye 50.0% - Ball')).toBe(true);
  });

  test('a back step from the stepper turns off eye notifications after 2 s', async () => {
    h = await startHarness();
    const device = await registerKnownDevice(h);
    getApp().globalData.selectedDevice = device;

    openProfileField(h, device.knownProfiles[0].id, 'EYE_THRESHOLD');
    await h.waitForText('Eye 50.0% - Empty');
    expect(eyeSubCalls(h.radio).map(c => c.enable)).toEqual([true]);

    h.back();
    await h.waitFor(() => eyeSubCalls(h.radio).some(c => c.enable === false));
    expect(eyeSubCalls(h.radio).map(c => c.enable)).toEqual([true, false]);
  });

  test('another field makes no eye subscribe and shows no eye text', async () => {
    h = await startHarness();
    const device = await registerKnownDevice(h);
    getApp().globalData.selectedDevice = device;

    openProfileField(h, device.knownProfiles[0].id, 'EYE_DEBOUNCE');
    await new Promise(resolve => setTimeout(resolve, 400));

    expect(eyeSubCalls(h.radio)).toHaveLength(0);
    expect(liveEyeText(h)).toBeNull();
  });

  test('an old board shows the firmware update hint', async () => {
    h = await startHarness({
      boardOptions: { eyeService: false },
      faults: { missingService: 'fail' }
    });
    const device = await registerKnownDevice(h);
    getApp().globalData.selectedDevice = device;

    openProfileField(h, device.knownProfiles[0].id, 'EYE_THRESHOLD');
    expect(h.hasText(LABELS.eyeUnsupported)).toBe(true);
  });

  test('a board in EYE_OFF shows the eye off hint', async () => {
    h = await startHarness();
    h.board.setEyeStatus({ fullScaleRaw: 4095, state: 2 });
    const device = await registerKnownDevice(h);
    getApp().globalData.selectedDevice = device;

    openProfileField(h, device.knownProfiles[0].id, 'EYE_THRESHOLD');
    await h.waitForText(LABELS.eyeOff);
  });

  test('a disconnect removes the eye text from the open stepper', async () => {
    h = await startHarness();
    const device = await registerKnownDevice(h);
    getApp().globalData.selectedDevice = device;

    openProfileField(h, device.knownProfiles[0].id, 'EYE_THRESHOLD');
    await h.waitForText('Eye 50.0% - Empty');

    await new Promise(resolve => h.ble.disconnect(resolve));
    expect(liveEyeText(h)).toBeNull();
    expect(h.hasText(LABELS.eyeEmpty)).toBe(false);
  });

  test('the mock board shows a live eye level that moves over time', async () => {
    h = await startHarness();
    getApp().globalData.mockDevicesEnabled = true;

    const { device } = await h.connect({ id: MOCK_DEVICE_ID, name: 'Snapshot PB', isMock: true });
    h.store.confirmNewDevice(device, 'Mock Board');
    const stored = h.store.getKnownDevices()[0];
    getApp().globalData.selectedDevice = stored;

    // Profile 1 of the mock board uses REFLECTIVE mode with threshold 60.
    const reflective = stored.knownProfiles[1];
    openProfileField(h, reflective.id, 'EYE_THRESHOLD');

    await h.waitFor(() => liveEyeText(h));
    const firstEyeText = liveEyeText(h);

    await h.waitFor(() => {
      const current = liveEyeText(h);
      return current && current !== firstEyeText;
    });
  });
});
