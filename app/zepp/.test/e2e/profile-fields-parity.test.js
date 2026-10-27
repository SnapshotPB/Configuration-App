// Parity of the watch's profile field lists with MarkerProfile and
// MarkerProfileField in core:model.
//
// The watch keeps hand-written copies of three lists:
// - PROFILE_FIELDS (src/utils/profileFields.js), the numeric fields the
//   stepper edits, with their units and limits.
// - PROFILE_REQUIRED_FIELDS and PROFILE_FIELD_DEFAULTS
//   (src/protocol/OtsModelMapper.js), every field of the domain profile and
//   the default of each. PROFILE_FIELD_NAMES comes from them. The divergence
//   check in DeviceStore compares those fields, and the default fill uses the
//   defaults.
//
// The Kotlin side exports each table as a JSON fixture and tests the fixture
// against its own model. This test reads the same fixtures, so a change on one
// side and not on the other fails CI. The Zepp workflow runs on a change under
// core/model for this reason.
//
// The Zepp table does not model the Kotlin `group`, so the test does not
// compare it.

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import '../mocks/runtime';
import { afterEach, describe, expect, test } from 'vitest';
import { startHarness } from '../harness';
import { snapshotpb } from '../../src/protocol/proto.js';
import {
  PROFILE_FIELDS,
  formatProfileFieldValue,
  profileFieldByKey,
  stepLabel
} from '../../src/utils/profileFields.js';
import {
  EyeMode,
  FireMode,
  PROFILE_FIELD_DEFAULTS,
  PROFILE_FIELD_NAMES,
  PROFILE_REQUIRED_FIELDS,
  getProfileUuid,
  toDomainDevice,
  toProtocolConfig
} from '../../src/protocol/OtsModelMapper.js';

function readFixture(name) {
  const path = fileURLToPath(
    new URL(`../../../../core/model/src/jvmTest/resources/${name}`, import.meta.url)
  );
  return JSON.parse(readFileSync(path, 'utf8'));
}

const { Device, DeviceConfig, Profiles } = snapshotpb.v1;

const FIELD_TABLE = readFixture('profile-fields.json');
const PROFILE_SHAPE = readFixture('marker-profile-fields.json');

// The domain values of each list-valued profile field.
const CHOICES = { fireMode: FireMode, eyeMode: EyeMode };

function comparable(field) {
  return {
    key: field.key,
    unit: field.unit,
    min: field.min,
    max: field.max,
    affectsMaxBps: field.affectsMaxBps
  };
}

function sorted(list) {
  return [...list].sort();
}

// Sends [message] through the generated codec, as the board link does.
function throughWire(type, message) {
  return type.decode(type.encode(type.create(message)).finish());
}

// A value of [field] that is not [value]. A number stays in the limits of its
// PROFILE_FIELDS entry, so the firmware clamp keeps it.
function otherValue(field, value) {
  if (typeof value === 'number') {
    const entry = PROFILE_FIELDS.find(f => f.field === field);
    return entry && value + 1 > entry.max ? value - 1 : value + 1;
  }
  const choices = CHOICES[field];
  if (choices) {
    const other = Object.values(choices).find(v => v !== value);
    if (other !== undefined) return other;
  }
  if (typeof value === 'string') return `${value}X`.slice(-10);
  throw new Error(`No other value is known for the profile field ${field}`);
}

// A profile that holds the MarkerProfile default in every defaulted field.
function defaultProfile(id) {
  return { id, name: 'Base', ...PROFILE_SHAPE.defaults };
}

describe('profile field table parity', () => {
  let h = null;

  afterEach(() => {
    if (h) h.teardown();
    h = null;
  });

  test('PROFILE_FIELDS matches MarkerProfileField in order, units, limits and max-BPS effect', () => {
    expect(PROFILE_FIELDS.map(comparable)).toEqual(FIELD_TABLE.map(comparable));
  });

  test('PROFILE_REQUIRED_FIELDS and PROFILE_FIELD_DEFAULTS match MarkerProfile', () => {
    expect([...PROFILE_REQUIRED_FIELDS]).toEqual(PROFILE_SHAPE.required);
    expect({ ...PROFILE_FIELD_DEFAULTS }).toEqual(PROFILE_SHAPE.defaults);
  });

  test('PROFILE_FIELD_NAMES holds every MarkerProfile field once', () => {
    const expected = [...PROFILE_SHAPE.required, ...Object.keys(PROFILE_SHAPE.defaults)];
    expect(sorted(PROFILE_FIELD_NAMES)).toEqual(sorted(expected));
    expect(new Set(PROFILE_FIELD_NAMES).size).toBe(PROFILE_FIELD_NAMES.length);
  });

  test('the Zepp lists leave out the fields that only the Kotlin app keeps', () => {
    // sourceSlot is not a setting, and the Kotlin divergence checks ignore it.
    Object.keys(PROFILE_SHAPE.appOnly).forEach((field) => {
      expect(PROFILE_FIELD_NAMES, field).not.toContain(field);
    });
  });

  test('PROFILE_FIELDS holds every numeric MarkerProfile field', () => {
    const numeric = Object.keys(PROFILE_SHAPE.defaults)
      .filter(field => typeof PROFILE_SHAPE.defaults[field] === 'number');
    expect(sorted(PROFILE_FIELDS.map(f => f.field))).toEqual(sorted(numeric));
  });

  test('every MarkerProfile field goes to the board and comes back', () => {
    const deviceIdBytes = Uint8Array.from({ length: 16 }, (_, i) => i + 1);
    const sent = { ...defaultProfile(getProfileUuid(deviceIdBytes, 0)), name: 'Parity' };
    Object.keys(PROFILE_SHAPE.defaults).forEach((field) => {
      sent[field] = otherValue(field, sent[field]);
    });

    const { deviceConfig, profiles } = toProtocolConfig({
      bootText: 'SNAP',
      activeProfileId: null,
      knownProfiles: [sent]
    });
    const read = toDomainDevice(
      throughWire(Device, { deviceId: deviceIdBytes, lockoutEnabled: false, model: 1, supportedProfileCount: 1 }),
      throughWire(DeviceConfig, deviceConfig),
      throughWire(Profiles, profiles),
      'Board',
      'AA:BB'
    ).knownProfiles[0];

    expect(sorted(Object.keys(read))).toEqual(sorted(PROFILE_FIELD_NAMES));
    PROFILE_FIELD_NAMES.forEach((field) => {
      expect(read[field], field).toBe(sent[field]);
    });
  });

  test('a board change in any MarkerProfile field raises a conflict', async () => {
    h = await startHarness();
    const { DeviceStore } = await import('../../src/store/DeviceStore.js');
    const base = defaultProfile('p-1');
    const device = profile => ({
      id: 'dev-1',
      name: 'Board',
      bootText: 'SNAP',
      activeProfileId: null,
      lockoutEnabled: false,
      language: null,
      screenBrightness: null,
      screenOffDelay: null,
      boltMode: null,
      knownProfiles: [profile]
    });
    // The watch holds a local edit of the boot text, and no profile edit. With no local edit, a
    // read takes the board values with no conflict. The boot text on the board does not change, so
    // only the profile field can make the board state differ from the baseline.
    const local = {
      ...device(base),
      bootText: 'EDIT',
      boardState: {
        bootText: 'SNAP',
        activeProfileId: null,
        lockoutEnabled: false,
        knownProfiles: [base],
        language: null,
        screenBrightness: null,
        screenOffDelay: null,
        boltMode: null
      }
    };
    const fields = [...PROFILE_SHAPE.required, ...Object.keys(PROFILE_SHAPE.defaults)];

    fields.forEach((field) => {
      const store = new DeviceStore();
      store.update([local]);
      const remote = device({ ...base, [field]: otherValue(field, base[field]) });
      expect(store.onDeviceRead(remote).type, field).toBe('Conflict');
    });

    // The control: the same board state is no conflict.
    const store = new DeviceStore();
    store.update([local]);
    expect(store.onDeviceRead(device({ ...base })).type).toBe('Synced');
  });
});

// The value texts match the KMP profile_value_* English strings, so the watch shows each unit the
// same way as the phone and the Wear OS app.
describe('profile field value text', () => {
  test('a timing field shows milliseconds with one decimal', () => {
    expect(formatProfileFieldValue(profileFieldByKey('SEAR_SOLENOID_DWELL'), 45)).toBe('4.5 ms');
  });

  test('the ramping multiplier shows the stored percent divided by ten as a multiple', () => {
    // The protocol stores 250 percent as 25, which is 2.5 times.
    const def = profileFieldByKey('RAMPING_CUSTOM_MULTIPLIER');
    expect(formatProfileFieldValue(def, 25)).toBe('2.5\u00D7');
    expect(stepLabel(def, 1)).toBe('+0.1');
    expect(stepLabel(def, -5)).toBe('-0.5');
  });

  test('the ramping minimum rate of fire shows whole balls per second', () => {
    const def = profileFieldByKey('RAMPING_CUSTOM_ROF_MINIMUM');
    expect(formatProfileFieldValue(def, 3)).toBe('3 BPS');
    expect(stepLabel(def, 1)).toBe('+1');
  });

  test('a count shows as a plain number', () => {
    expect(formatProfileFieldValue(profileFieldByKey('RAMPING_CUSTOM_TRIGGER_PULLS'), 3)).toBe('3');
  });
});
