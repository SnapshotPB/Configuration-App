// Writing configuration back to the board: the OACP WRITE handshake, the
// chunked ObjectData transfer, and the read-back the app performs afterwards —
// all decoded by the board with the same generated protobuf bindings the watch
// encoded with.

import { afterEach, beforeEach, describe, expect, test } from 'vitest';
import { startHarness } from '../harness';
import { BATTERY_TYPE, FIRE_MODE } from '../virtual-board/boardState';
import { profileChoiceLabel } from '../../src/utils/profileFields';

const OBJECT_DATA_UUID = '00000002-5350-4221-0000-5350425F4F54';

describe('write device configuration', () => {
  let h;

  beforeEach(async () => {
    h = await startHarness();
  });

  afterEach(() => {
    if (h) h.teardown();
    h = null;
  });

  test('writes device_config and profiles back, and the board decodes them', async () => {
    const { device } = await h.connect();

    device.bootText = 'NEWBOOT';
    device.activeProfileId = h.profileUuid(1); // slot 1 == 2 on the 1-based wire
    device.knownProfiles[0].fireRateCapTenths = 135;
    device.knownProfiles[0].fireMode = 'FULL_AUTO';
    device.knownProfiles[0].searSolenoidDwell = 72;

    await h.writeConfiguration(h.address, device);

    expect(h.board.state.deviceConfig.bootText).toBe('NEWBOOT');
    expect(h.board.state.deviceConfig.activeProfile).toBe(2);
    expect(h.board.state.profiles.profiles[0].fireRateCap).toBe(135);
    expect(h.board.state.profiles.profiles[0].autococker.fireMode).toBe(
      FIRE_MODE.AUTOCOCKER_FIRE_MODE_FULL_AUTO
    );
    expect(h.board.state.profiles.profiles[0].autococker.searSolenoidDwell).toBe(72);
    expect(h.board.state.profiles.profiles[1].name).toBe('Ramp');

    // Both writable objects were applied; neither decode failed.
    expect(h.board.appliedWrites.map(w => w.name)).toEqual(['device_config', 'profiles']);
    expect(h.board.appliedWrites.every(w => w.error === null)).toBe(true);
  });

  test('refuses a write with no profiles before any object goes', async () => {
    // A Profiles object with no profiles has zero length, and the board keeps at least one
    // profile. The guard must stop the save before device_config goes, or the board keeps half.
    const { device } = await h.connect();
    device.bootText = 'EMPTY';
    device.knownProfiles = [];
    device.activeProfileId = null;

    await expect(h.writeConfiguration(h.address, device)).rejects.toThrow(/no profiles/);

    expect(h.board.appliedWrites).toEqual([]);
    expect(h.board.state.deviceConfig.bootText).not.toBe('EMPTY');
  });

  test('carries board-owned profile fields forward instead of resetting them', async () => {
    // board_auto_off is in the shared schema but not in the watch's domain
    // model. A save rewrites the whole Profiles object, so OtsClient feeds the
    // last read back into toProtocolConfig and each slot inherits its own value.
    expect(h.board.state.profiles.profiles[0].boardAutoOff).toBe(15);

    const { device } = await h.connect();
    await h.writeConfiguration(h.address, device);

    expect(h.board.state.profiles.profiles[0].boardAutoOff).toBe(15);
    expect(h.board.state.profiles.profiles[1].boardAutoOff).toBe(15);
  });

  test('leaves board display settings alone when the watch holds none for them', async () => {
    // The op takes a WHOLE DeviceConfig, so a watch that only means to change
    // boot_text still sends the zero value for language, brightness and delay.
    // device_config.proto defines those zeros as "leave the board's value alone",
    // and toProtocolConfig omits the fields rather than sending a chosen zero.
    // This is the test that proves the app gets that right: it can only pass on a
    // board that merges, and it fails on one that replaces wholesale.
    const { device } = await h.connect();
    device.language = null;
    device.screenBrightness = null;
    device.screenOffDelay = null;

    await h.writeConfiguration(h.address, device);

    const cfg = h.board.state.deviceConfig;
    expect(cfg.language).toBe(1); // LANGUAGE_EN, untouched
    expect(cfg.screenBrightness).toBe(2); // DEFAULT, untouched
    expect(cfg.screenOffDelay).toBe(5); // untouched
    // display_window has explicit message presence and the watch never sends one.
    expect(cfg.displayWindow).toEqual({ x: 0, y: 3, w: 110, h: 26 });
    // The watch has no battery setting. A write must not move a 2S LiPo board to a 9 V cell.
    expect(cfg.batteryType).toBe(BATTERY_TYPE.BATTERY_TYPE_2S_LIPO);
    // ...and the fields the watch DID send still applied.
    expect(cfg.bootText).toBe('SNAPSHOT');
    expect(cfg.activeProfile).toBe(1);
  });

  test('writes the board display settings the watch does hold', async () => {
    const { device } = await h.connect();
    device.language = 'FR';
    device.screenBrightness = 'HIGH';
    device.screenOffDelay = 42;

    await h.writeConfiguration(h.address, device);

    const cfg = h.board.state.deviceConfig;
    expect(cfg.language).toBe(4); // LANGUAGE_FR
    expect(cfg.screenBrightness).toBe(3); // HIGH
    expect(cfg.screenOffDelay).toBe(42);
    expect(cfg.displayWindow).toEqual({ x: 0, y: 3, w: 110, h: 26 });
  });

  test('uses OACP WRITE, chunks into ObjectData, then reads device_config back', async () => {
    const { device } = await h.connect();
    const callsBefore = h.radio.calls.length;

    await h.writeConfiguration(h.address, device);

    const writes = h.radio.calls.slice(callsBefore).filter(c => c.fn === 'mstWriteCharacteristic');
    // One OACP WRITE (0x06) per writable object, then a READ (0x05): the app
    // re-reads device_config so the store settles on what the board actually
    // kept (it ignores a language it does not know and clamps the delay).
    expect(writes.filter(c => c.uuid === '2AC5').map(c => c.hex.slice(0, 2))).toEqual(['06', '06', '05']);

    const dataWrites = writes.filter(c => c.uuid.toUpperCase() === OBJECT_DATA_UUID);
    expect(dataWrites.length).toBeGreaterThan(1);
    // 20 bytes is the conservative single-ATT-packet size OtsClient writes.
    expect(dataWrites.every(c => c.hex.length / 2 <= 20)).toBe(true);

    // The read-only Device object is never written by a client.
    expect(h.board.appliedWrites.some(w => w.name === 'device')).toBe(false);
  });

  test('shows a mode it does not know as unknown and writes the board number back', async () => {
    // Newer firmware can report a fire mode or an eye mode that this app does not know. The
    // editor must not show a mode that the board does not run, and a save must not change it.
    h.board.state.profiles.profiles[0].autococker.fireMode = 42;
    h.board.state.profiles.profiles[0].autococker.eyeMode = 9;

    const { device } = await h.connect();
    const profile = device.knownProfiles[0];
    expect(profile.fireMode).toBe('UNKNOWN');
    expect(profile.eyeMode).toBe('UNKNOWN');
    expect(profileChoiceLabel('fireMode', profile.fireMode)).toBe('Unknown Mode');
    expect(profileChoiceLabel('eyeMode', profile.eyeMode)).toBe('Unknown Mode');

    device.bootText = 'EDITED';
    await h.writeConfiguration(h.address, device);

    expect(h.board.state.profiles.profiles[0].autococker.fireMode).toBe(42);
    expect(h.board.state.profiles.profiles[0].autococker.eyeMode).toBe(9);
  });

  test('a mode that the user selects replaces the unknown one', async () => {
    h.board.state.profiles.profiles[0].autococker.fireMode = 42;
    h.board.state.profiles.profiles[0].autococker.eyeMode = 9;

    const { device } = await h.connect();
    device.knownProfiles[0].fireMode = 'MECHANICAL';

    await h.writeConfiguration(h.address, device);

    expect(h.board.state.profiles.profiles[0].autococker.fireMode).toBe(
      FIRE_MODE.AUTOCOCKER_FIRE_MODE_MECHANICAL
    );
    // The eye mode was not touched, so the board keeps its own number.
    expect(h.board.state.profiles.profiles[0].autococker.eyeMode).toBe(9);
  });

  test('a read/write round trip preserves everything the domain model carries', async () => {
    const { device } = await h.connect();
    await h.writeConfiguration(h.address, device);

    const { device: reread } = await h.connect();
    expect(reread.bootText).toBe(device.bootText);
    expect(reread.activeProfileId).toBe(device.activeProfileId);
    expect(reread.language).toBe(device.language);
    expect(reread.screenBrightness).toBe(device.screenBrightness);
    expect(reread.screenOffDelay).toBe(device.screenOffDelay);
    expect(reread.knownProfiles).toEqual(device.knownProfiles.map(p => ({ ...p })));
  });

  test('truncates boot text and profile names to the firmware buffer limits', async () => {
    const { device } = await h.connect();
    device.bootText = 'WAY TOO LONG BOOT TEXT';
    device.knownProfiles[0].name = 'An Extremely Long Profile Name';

    await h.writeConfiguration(h.address, device);

    // boot_text is a nanopb char[11] => 10 usable bytes.
    expect(h.board.state.deviceConfig.bootText).toBe('WAY TOO LO');
    // Profile.name is a char[11] => 10 usable bytes, NOT 11: an 11-byte payload
    // overflows and the WHOLE Profiles message fails to decode on the board.
    expect(h.board.state.profiles.profiles[0].name).toBe('An Extreme');
  });
});
