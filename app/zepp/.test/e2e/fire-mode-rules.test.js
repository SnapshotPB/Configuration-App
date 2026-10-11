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
import { createBoardState, createProfile, FIRE_MODE } from '../virtual-board/boardState';
import {
  EyeMode,
  FireMode,
  fireModeToDomain,
  fireModeToProto
} from '../../src/protocol/OtsModelMapper.js';
import { snapshotpb } from '../../src/protocol/proto.js';
import {
  PROFILE_CHOICES,
  hasFixedRateCap,
  profileChoiceLabel,
  usesCockingTimeout,
  usesCustomRamping,
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
});
