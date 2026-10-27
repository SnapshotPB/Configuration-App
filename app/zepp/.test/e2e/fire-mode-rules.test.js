// The two fire mode rules of the board menu, as the watch editor applies them. They mirror the
// rules of MarkerProfileField in core:model, which MarkerProfileFieldTest covers.
//
// - The league sets the rules of the NXL 2026 mode, so the editor shows no setting of that mode:
//   not the fire-rate cap, and not the ramping fields.
// - The three ramping fields are the settings of the custom ramping mode. No other mode reads
//   them, so the editor shows them in that mode only.

import '../mocks/runtime';
import { afterEach, describe, expect, test } from 'vitest';
import { startHarness } from '../harness';
import { LABELS, PAGES, syncNewBoard } from '../harness/flows';
import { BOLT_MODE, createBoardState, createProfile, FIRE_MODE } from '../virtual-board/boardState';
import {
  EyeMode,
  FireMode,
  MarkerBoltMode,
  boltModeToDomain,
  boltModeToProto,
  fireModeToDomain,
  fireModeToProto
} from '../../src/protocol/OtsModelMapper.js';
import { snapshotpb } from '../../src/protocol/proto.js';
import { BOARD_SETTINGS, boardSettingLabel } from '../../src/utils/boardSettings.js';
import {
  PROFILE_CHOICES,
  hasFixedRateCap,
  isFieldShown,
  profileChoiceLabel,
  usesBoltMode,
  usesCockingTimeout,
  usesCustomRamping,
  usesOpenBolt,
  visibleProfileFields
} from '../../src/utils/profileFields.js';

const WIRE = snapshotpb.autococker.v1.AutocockerFireMode;

const RAMPING_KEYS = [
  'RAMPING_CUSTOM_MULTIPLIER',
  'RAMPING_CUSTOM_TRIGGER_PULLS',
  'RAMPING_CUSTOM_ROF_MINIMUM'
];

function visibleKeys(fireMode, eyeMode = EyeMode.REFLECTIVE) {
  return visibleProfileFields({ fireMode, eyeMode }).map(f => f.key);
}

describe('fire mode rules', () => {
  test('the ramping fields show in the custom ramping mode only', () => {
    for (const fireMode of Object.values(FireMode)) {
      const keys = visibleKeys(fireMode);
      for (const key of RAMPING_KEYS) {
        expect(keys.includes(key), `${key} in ${fireMode}`).toBe(fireMode === FireMode.CUSTOM_RAMPING);
      }
    }
  });

  test('only the NXL 2026 mode hides the fire-rate cap', () => {
    for (const fireMode of Object.values(FireMode)) {
      expect(visibleKeys(fireMode).includes('FIRE_RATE_CAP'), fireMode)
        .toBe(fireMode !== FireMode.RAMPING_NXL_2026);
    }
  });

  test('the NXL 2026 mode shows no setting of its own and keeps the others', () => {
    // The eye, timing and trigger fields set how the marker operates, so they stay.
    expect(visibleKeys(FireMode.RAMPING_NXL_2026)).toEqual([
      'EYE_THRESHOLD',
      'EYE_DEBOUNCE',
      'COCKING_SOLENOID_TIMEOUT',
      'SEAR_SOLENOID_DWELL',
      'COCKING_SOLENOID_DELAY',
      'COCKING_SOLENOID_DWELL_OPEN',
      'COCKING_SOLENOID_DWELL_CLOSED',
      'TRIGGER_DEBOUNCE_ON',
      'TRIGGER_DEBOUNCE_OFF'
    ]);
  });

  test('each rule names one mode', () => {
    const modes = Object.values(FireMode);
    expect(modes.filter(hasFixedRateCap)).toEqual([FireMode.RAMPING_NXL_2026]);
    expect(modes.filter(usesCustomRamping)).toEqual([FireMode.CUSTOM_RAMPING]);
  });

  test('the two ramping modes have an eye-gated cocking phase', () => {
    expect(Object.values(FireMode).filter(usesCockingTimeout)).toEqual([
      FireMode.SEMI,
      FireMode.FULL_AUTO,
      FireMode.RAMPING_NXL_2026,
      FireMode.CUSTOM_RAMPING
    ]);
  });

  test('bolt mode applies only in the cycled fire modes', () => {
    expect(Object.values(FireMode).filter(usesBoltMode)).toEqual([
      FireMode.SEMI,
      FireMode.FULL_AUTO,
      FireMode.RAMPING_NXL_2026,
      FireMode.CUSTOM_RAMPING
    ]);
    for (const fireMode of [FireMode.MECHANICAL, FireMode.TRIGGER_ONLY, FireMode.UNKNOWN]) {
      expect(usesOpenBolt({ fireMode }, MarkerBoltMode.OPEN), fireMode).toBe(false);
    }
    for (const fireMode of [FireMode.SEMI, FireMode.FULL_AUTO, FireMode.RAMPING_NXL_2026, FireMode.CUSTOM_RAMPING]) {
      expect(usesOpenBolt({ fireMode }, MarkerBoltMode.CLOSED), fireMode).toBe(false);
      expect(usesOpenBolt({ fireMode }, MarkerBoltMode.OPEN), fireMode).toBe(true);
    }
  });

  test('open bolt swaps Cocking Solenoid Delay for Sear Solenoid Delay in cycled fire modes only', () => {
    for (const fireMode of Object.values(FireMode)) {
      const closedKeys = visibleProfileFields({
        fireMode,
        eyeMode: EyeMode.REFLECTIVE
      }, MarkerBoltMode.CLOSED).map(f => f.key);
      expect(closedKeys, `closed ${fireMode}`).toContain('COCKING_SOLENOID_DELAY');
      expect(closedKeys, `closed ${fireMode}`).not.toContain('SEAR_SOLENOID_DELAY');

      const openKeys = visibleProfileFields({
        fireMode,
        eyeMode: EyeMode.REFLECTIVE
      }, MarkerBoltMode.OPEN).map(f => f.key);
      const isOpen = usesBoltMode(fireMode);
      expect(openKeys.includes('SEAR_SOLENOID_DELAY'), `open SEAR_SOLENOID_DELAY in ${fireMode}`).toBe(isOpen);
      expect(openKeys.includes('COCKING_SOLENOID_DELAY'), `open COCKING_SOLENOID_DELAY in ${fireMode}`).toBe(!isOpen);
      expect(isFieldShown('SEAR_SOLENOID_DELAY', { fireMode }, MarkerBoltMode.OPEN)).toBe(isOpen);
      expect(isFieldShown('COCKING_SOLENOID_DELAY', { fireMode }, MarkerBoltMode.OPEN)).toBe(!isOpen);
    }
  });

  test('bolt mode maps between wire DeviceConfig.bolt_mode enum and MarkerBoltMode domain values', () => {
    expect(boltModeToDomain(BOLT_MODE.BOLT_MODE_UNSPECIFIED)).toBeNull();
    expect(boltModeToDomain(BOLT_MODE.BOLT_MODE_CLOSED)).toBe(MarkerBoltMode.CLOSED);
    expect(boltModeToDomain(BOLT_MODE.BOLT_MODE_OPEN)).toBe(MarkerBoltMode.OPEN);
    expect(boltModeToDomain(99)).toBeNull();
    expect(boltModeToProto(null)).toBe(BOLT_MODE.BOLT_MODE_UNSPECIFIED);
    expect(boltModeToProto(MarkerBoltMode.CLOSED)).toBe(BOLT_MODE.BOLT_MODE_CLOSED);
    expect(boltModeToProto(MarkerBoltMode.OPEN)).toBe(BOLT_MODE.BOLT_MODE_OPEN);
    expect(BOARD_SETTINGS.boltMode.options).toEqual([MarkerBoltMode.CLOSED, MarkerBoltMode.OPEN]);
    expect(boardSettingLabel('boltMode', MarkerBoltMode.CLOSED)).toBe('Closed Bolt');
    expect(boardSettingLabel('boltMode', MarkerBoltMode.OPEN)).toBe('Open Bolt');
  });
});

describe('the two ramping modes', () => {
  test('each fire mode of the protocol has its own app mode', () => {
    // A new mode of the protocol must not read as a mode that the app already has.
    const numbers = Object.values(WIRE).filter(n => n !== WIRE.AUTOCOCKER_FIRE_MODE_UNSPECIFIED);
    const modes = numbers.map(fireModeToDomain);

    expect(modes).not.toContain(FireMode.UNKNOWN);
    expect(new Set(modes).size).toBe(numbers.length);
    expect(modes.map(fireModeToProto)).toEqual(numbers);
  });

  test('the two ramping modes use the numbers of the protocol', () => {
    expect(fireModeToProto(FireMode.RAMPING_NXL_2026)).toBe(WIRE.AUTOCOCKER_FIRE_MODE_RAMPING_NXL_2026);
    expect(fireModeToProto(FireMode.CUSTOM_RAMPING)).toBe(WIRE.AUTOCOCKER_FIRE_MODE_CUSTOM_RAMPING);
  });

  test('the NXL mode keeps the value that older builds stored', () => {
    // A stored profile holds 'RAMPING_CUSTOM' for the mode that the board holds as NXL 2026.
    expect(FireMode.RAMPING_NXL_2026).toBe('RAMPING_CUSTOM');
    expect(fireModeToProto('RAMPING_CUSTOM')).toBe(WIRE.AUTOCOCKER_FIRE_MODE_RAMPING_NXL_2026);
    expect(FireMode.CUSTOM_RAMPING).toBe('CUSTOM_RAMPING');
  });

  test('the editor offers the two modes with the names of the board menu', () => {
    expect(PROFILE_CHOICES.fireMode.options).toContain(FireMode.RAMPING_NXL_2026);
    expect(PROFILE_CHOICES.fireMode.options).toContain(FireMode.CUSTOM_RAMPING);
    expect(profileChoiceLabel('fireMode', FireMode.RAMPING_NXL_2026)).toBe('NXL 2026');
    expect(profileChoiceLabel('fireMode', FireMode.CUSTOM_RAMPING)).toBe('Custom Ramping');
  });
});

// The same rules through the pages, with a board on the link. A rule that holds in
// visibleProfileFields and not on the page fails here.
describe('the profile editor with a board', () => {
  let h = null;

  afterEach(() => {
    if (h) h.teardown();
    h = null;
  });

  // Taps the newest widget with a matching text. A page lower in the stack keeps its widgets,
  // and the device page below the editor also has a Save to Board button. The editor draws its
  // widgets after the device page, so the newest match is the widget of the editor.
  function clickNewest(predicate) {
    const matches = h.ui.findAll(w => typeof w.props.text === 'string' && predicate(w.props.text));
    expect(matches.length, 'a widget to tap').toBeGreaterThan(0);
    return h.clickWidget(matches[matches.length - 1]);
  }

  function expectRows({ cap, ramping }) {
    // The first line of each row is the label of its field. Only the editor has these rows.
    const labels = h.texts().map(text => text.split('\n')[0]);
    expect(labels.includes('Fire Rate Cap'), 'Fire Rate Cap').toBe(cap);
    for (const label of ['Ramping Custom Multiplier', 'Ramping Custom Trigger Pulls', 'Ramping Custom ROF Minimum']) {
      expect(labels.includes(label), label).toBe(ramping);
    }
    // The fields that set how the marker operates show in each mode.
    expect(labels).toContain('Sear Solenoid Dwell');
    expect(labels).toContain('Trigger Debounce On');
    expect(labels).toContain('Eye Threshold');
  }

  function expectMaxBps(value) {
    expect(h.texts().filter(text => text.startsWith('Max BPS'))).toEqual([`Max BPS: ${value}`]);
  }

  // Opens the fire mode list from the profile editor and selects the mode with this name.
  function selectFireMode(name) {
    clickNewest(text => text.startsWith('Fire Mode'));
    expect(h.stack[h.stack.length - 1].__url).toBe(PAGES.boardSettingSelection);
    clickNewest(text => text === name);
    expect(h.stack[h.stack.length - 1].__url).toBe(PAGES.profileEdit);
  }

  async function saveAndWaitForFireMode(wireValue) {
    const editor = h.stack[h.stack.length - 1];
    clickNewest(text => text === LABELS.saveToBoard);
    await h.waitFor(() => h.board.state.profiles.profiles[0].autococker.fireMode === wireValue && !editor.saving);
  }

  test('the rows follow the fire mode, and a save keeps the values of the hidden rows', async () => {
    // The stored cap is above the league limit, so the max BPS shows which cap applies.
    h = await startHarness({
      state: createBoardState({
        profiles: { profiles: [createProfile({ name: 'Semi Auto', fireRateCap: 150 })] }
      })
    });
    await syncNewBoard(h);
    h.click(LABELS.tuneProfile);
    expect(h.stack[h.stack.length - 1].__url).toBe(PAGES.profileEdit);

    // Semi: the cap shows, and the ramping rows do not.
    expectRows({ cap: true, ramping: false });
    expectMaxBps('15.0');

    // NXL 2026: the league sets its rules, so no setting of the mode shows.
    selectFireMode('NXL 2026');
    expectRows({ cap: false, ramping: false });
    expectMaxBps('10.5');

    // Custom Ramping: the cap and the three ramping rows show.
    selectFireMode('Custom Ramping');
    expectRows({ cap: true, ramping: true });
    expectMaxBps('15.0');

    await saveAndWaitForFireMode(FIRE_MODE.AUTOCOCKER_FIRE_MODE_CUSTOM_RAMPING);

    // A save in the NXL 2026 mode still sends the cap and the ramping values, so the board
    // keeps them for the next mode.
    selectFireMode('NXL 2026');
    await saveAndWaitForFireMode(FIRE_MODE.AUTOCOCKER_FIRE_MODE_RAMPING_NXL_2026);

    const onBoard = h.board.state.profiles.profiles[0];
    expect(onBoard.fireRateCap).toBe(150);
    expect(onBoard.autococker.rampingCustomMultiplier).toBe(20);
    expect(onBoard.autococker.rampingCustomTriggerPulls).toBe(4);
    expect(onBoard.autococker.rampingCustomRofMinimum).toBe(60);
    expect(h.board.appliedWrites.every(w => w.error === null)).toBe(true);
  });

  test('a board in the custom ramping mode reads as that mode', async () => {
    h = await startHarness({
      state: createBoardState({
        profiles: {
          profiles: [
            createProfile({
              name: 'Custom',
              autococker: { fireMode: FIRE_MODE.AUTOCOCKER_FIRE_MODE_CUSTOM_RAMPING }
            })
          ]
        }
      })
    });

    const { device } = await h.connect();

    expect(device.knownProfiles[0].fireMode).toBe(FireMode.CUSTOM_RAMPING);
    expect(profileChoiceLabel('fireMode', device.knownProfiles[0].fireMode)).toBe('Custom Ramping');
  });

  test('device Bolt Mode card and profile Sear Solenoid Delay follow Open Bolt and preserve hidden values on save', async () => {
    h = await startHarness({
      state: createBoardState({
        profiles: {
          profiles: [
            createProfile({
              name: 'Semi Auto',
              fireRateCap: 0,
              autococker: {
                cockingSolenoidDelay: 40,
                searSolenoidDelay: 75
              }
            })
          ]
        }
      })
    });
    await syncNewBoard(h);
    expect(h.stack[h.stack.length - 1].__url).toBe(PAGES.deviceConfig);
    expect(h.hasText('BOLT MODE')).toBe(true);
    expect(h.hasText('Closed Bolt')).toBe(true);

    h.click(LABELS.tuneProfile);
    expect(h.stack[h.stack.length - 1].__url).toBe(PAGES.profileEdit);

    const rowLabels = () => h.texts().map(text => text.split('\n')[0].replace(/ \*$/, ''));

    // Closed Bolt in Semi: Cocking Solenoid Delay shows; Sear Solenoid Delay hides.
    expect(rowLabels()).toContain('Cocking Solenoid Delay');
    expect(rowLabels()).not.toContain('Sear Solenoid Delay');

    // Return to device_config and switch the device-level Bolt Mode to Open Bolt.
    h.back();
    expect(h.stack[h.stack.length - 1].__url).toBe(PAGES.deviceConfig);
    clickNewest(text => text === 'Closed Bolt');
    expect(h.stack[h.stack.length - 1].__url).toBe(PAGES.boardSettingSelection);
    clickNewest(text => text === 'Open Bolt');
    expect(h.stack[h.stack.length - 1].__url).toBe(PAGES.deviceConfig);

    // Re-open profile editor: Sear Solenoid Delay now replaces Cocking Solenoid Delay in Semi.
    clickNewest(text => text.startsWith(LABELS.tuneProfile));
    expect(h.stack[h.stack.length - 1].__url).toBe(PAGES.profileEdit);
    expect(rowLabels()).toContain('Sear Solenoid Delay');
    expect(rowLabels()).not.toContain('Cocking Solenoid Delay');

    // Switch to Mechanical: Sear Solenoid Delay hides and Cocking Solenoid Delay returns.
    selectFireMode('Mechanical');
    expect(rowLabels()).not.toContain('Sear Solenoid Delay');
    expect(rowLabels()).toContain('Cocking Solenoid Delay');

    // Save in Mechanical: the board still receives boltMode = BOLT_MODE_OPEN on device_config,
    // plus searSolenoidDelay = 75 and cockingSolenoidDelay = 40 on the profile.
    await saveAndWaitForFireMode(FIRE_MODE.AUTOCOCKER_FIRE_MODE_MECHANICAL);

    expect(h.board.state.deviceConfig.boltMode).toBe(BOLT_MODE.BOLT_MODE_OPEN);
    const onBoard = h.board.state.profiles.profiles[0].autococker;
    expect(onBoard.searSolenoidDelay).toBe(75);
    expect(onBoard.cockingSolenoidDelay).toBe(40);
  });
});
