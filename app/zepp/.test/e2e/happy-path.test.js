// The full happy path, driven through the real pages: scan on the device list,
// tap a discovered board, watch the 4-step connect flow run a real OTS handshake
// against the virtual board, and land the decoded state in DeviceStore.
//
// This is the load-bearing test. If only one test survives a refactor of the
// app, it should be this one.

import { afterEach, beforeEach, describe, expect, test } from 'vitest';
import { startHarness } from '../harness';
import { LABELS, PAGES, syncNewBoard } from '../harness/flows';

describe('scan -> connect -> OTS read -> DeviceStore', () => {
  let h;

  beforeEach(async () => {
    h = await startHarness();
  });

  afterEach(() => {
    h.teardown();
  });

  test('discovers the board, reads it over OTS, and stores the decoded device', async () => {
    h.open(PAGES.deviceList);
    expect(h.hasText(LABELS.noSynced)).toBe(true);

    const stored = await syncNewBoard(h);
    expect(h.store.getKnownDevices()).toHaveLength(1);

    // The device id is the protobuf device UUID; the BLE address is kept as mac.
    expect(stored.id).toBe(h.deviceUuid());
    expect(stored.mac).toBe(h.address);
    // The new-device prompt names the device from the advertisement plus the last
    // four characters of the board UUID, so two boards never share a name.
    expect(stored.name).toBe(`Snapshot PB ${h.deviceUuid().replace(/-/g, '').slice(-4).toUpperCase()}`);
    expect(stored.bootText).toBe('SNAPSHOT');
    expect(stored.lockoutEnabled).toBe(false);
    expect(stored.boardModel).toBe('AUTOCOCKER');
    expect(stored.supportedProfileCount).toBe(4);
    // Board-level display settings, read from DeviceConfig.
    expect(stored.language).toBe('EN');
    expect(stored.screenBrightness).toBe('DEFAULT');
    expect(stored.screenOffDelay).toBe(5);

    expect(stored.knownProfiles).toHaveLength(2);
    expect(stored.knownProfiles[0]).toMatchObject({
      id: h.profileUuid(0),
      name: 'Semi Auto',
      fireMode: 'SEMI',
      // TENTHS of a ball per second: 105 is 10.5 BPS.
      fireRateCapTenths: 105,
      eyeMode: 'REFLECTIVE',
      // eye_threshold is a 0-100 percent of ADC full scale.
      eyeThreshold: 60,
      eyeDebounce: 8,
      searSolenoidDwell: 55,
      cockingSolenoidDelay: 15,
      cockingSolenoidDwellOpen: 25,
      cockingSolenoidDwellClosed: 35,
      rampingCustomMultiplier: 20,
      rampingCustomTriggerPulls: 4,
      rampingCustomROFMinimum: 60,
      triggerDebounceOn: 4,
      triggerDebounceOff: 4,
      cockingSolenoidTimeout: 5000
    });
    expect(stored.knownProfiles[1]).toMatchObject({
      id: h.profileUuid(1),
      name: 'Ramp',
      // The board holds RAMPING_NXL_2026. Its stored value is the name that older builds wrote.
      fireMode: 'RAMPING_CUSTOM',
      fireRateCapTenths: 125,
      eyeMode: 'BREAK_BEAM'
    });

    // active_profile is 1-based on the wire; slot 0 is the resolved UUID.
    expect(stored.activeProfileId).toBe(h.profileUuid(0));

    // And the flow handed off to the configuration page.
    expect(h.router.urls()).toContain(`replace:${PAGES.deviceConfig}`);
    expect(h.hasText(LABELS.activeProfile)).toBe(true);
    expect(h.hasText('Semi Auto')).toBe(true);
  });

  test('walks the object list and transfers every object over the vendor ObjectData characteristic', async () => {
    const { progress } = await h.connect();

    // The 4-step connect flow runs to completion: link, services, device info,
    // profiles. Step 3 completing is driven by the OTS 'deviceRead' phase.
    expect(progress[progress.length - 1]).toEqual({
      step1: h.StepStatus.COMPLETED,
      step2: h.StepStatus.COMPLETED,
      step3: h.StepStatus.COMPLETED,
      step4: h.StepStatus.COMPLETED
    });

    // OLCP walk: the first object needs one FIRST, each later one a FIRST plus
    // as many NEXTs as its position (0x01 = FIRST, 0x04 = NEXT).
    const olcpWrites = h.radio.callsTo('mstWriteCharacteristic').filter(c => c.uuid === '2AC6');
    expect(olcpWrites.map(c => c.hex)).toEqual(['01', '01', '04', '01', '04', '04']);

    // One OACP READ handshake (opcode 0x05) per object.
    const oacpWrites = h.radio.callsTo('mstWriteCharacteristic').filter(c => c.uuid === '2AC5');
    expect(oacpWrites.map(c => c.hex.slice(0, 2))).toEqual(['05', '05', '05']);

    // Content came over the vendor ObjectData characteristic, never L2CAP.
    expect(
      h.radio
        .callsTo('mstPrepareNotificationChar')
        .some(c => c.uuid.toUpperCase() === '00000002-5350-4221-0000-5350425F4F54' && c.enable === true)
    ).toBe(true);

    // Nothing was pushed at a characteristic the client had not subscribed to.
    expect(h.board.droppedNotifications).toEqual([]);
  });

  test('a second read of unchanged board state reconciles as Synced', async () => {
    const stored = await syncNewBoard(h);
    expect(stored).toBeDefined();

    const { device } = await h.connect();
    const result = h.store.onDeviceRead(device);
    expect(result.type).toBe('Synced');
    expect(h.store.getKnownDevices()).toHaveLength(1);
    // A Synced reconciliation must not clobber the user-facing name.
    expect(h.store.getKnownDevices()[0].name).toBe(stored.name);
  });

  test('trims the firmware zero padding before decoding', async () => {
    // The board pads every object out to its flash slot; ObjectSize reports the
    // padded length, so the client receives trailing zeros it has to trim.
    const deviceObject = h.board.objectBytes('device');
    expect(deviceObject.length % 32).toBe(0);
    expect(deviceObject[deviceObject.length - 1]).toBe(0);

    const { device } = await h.connect();
    expect(device.id).toBe(h.deviceUuid());
  });
});
