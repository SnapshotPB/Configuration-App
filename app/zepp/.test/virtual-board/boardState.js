// The virtual board's state, and the protobuf encode/decode that backs its OTS
// objects.
//
// Everything here is driven by the generated bindings in src/protocol/proto.js —
// the SAME code the watch runs, produced by `npm run build-proto` from the shared
// Configuration-Protocol submodule the KMP and Wear clients build against.
// Nothing hand-rolls protocol bytes, and nothing enumerates message fields:
// encoding is `<Type>.create` + `.encode`, decoding is `<Type>.toObject`. A
// schema bump changes the fixture and nothing else.
//
// WIRE FORMAT: each OTS object is a BARE per-object message — a raw Device,
// Profiles or DeviceConfig — NOT wrapped in the snapshotpb.v1.Message envelope.
// That is what the firmware and the KMP reference client exchange, and what
// OtsClient decodes (see its `const { Device, DeviceConfig, Profiles }` import).

// Must precede the generated bindings: it pins the byte representation the
// watch's JS runtime uses. See .test/mocks/runtime.js.
import '../mocks/runtime';
import { snapshotpb } from '../../src/protocol/proto';

const { Device, DeviceConfig, Profiles } = snapshotpb.v1;

/**
 * The board's OTS object list, in the order OLCP FIRST/NEXT walks it.
 *
 * `name` is the OTS object name the client selects by, `key` the board-state key,
 * `type` the generated message the object's bytes ARE. Adding an object to the
 * protocol is one row here plus a matching key in the state fixture.
 *
 * A write REPLACES the object wholesale unless the descriptor says otherwise.
 * DeviceConfig is the exception: the op takes a whole message, so a client that
 * only wants to change boot_text necessarily sends the zero value for every
 * other field — and device_config.proto defines those zeros as "leave the
 * board's current value alone" rather than "set it to zero". Modelling that here
 * is what lets a test prove the app OMITS a setting it has no value for; a board
 * that wiped on write would punish exactly the behaviour the protocol asks for.
 */
export const OTS_OBJECTS = [
  { name: 'device', key: 'device', type: Device, writable: false },
  { name: 'profiles', key: 'profiles', type: Profiles, writable: true },
  {
    name: 'device_config',
    key: 'deviceConfig',
    type: DeviceConfig,
    writable: true,
    // Fields the board keeps when the client says nothing for them.
    // display_window has explicit message presence; the rest are "0 = leave
    // alone" scalars/enums (device_config.proto fields 3-7).
    mergeOnZero: ['displayWindow', 'language', 'screenBrightness', 'screenOffDelay', 'batteryType'],
    // The board clamps rather than rejects, so the other fields still apply.
    clampMax: { screenOffDelay: 999 }
  }
];

export function objectByName(name) {
  return OTS_OBJECTS.find(o => o.name === name) || null;
}

// Byte 15 (0x10) is what OtsModelMapper.getProfileUuid XORs with the slot index,
// so slots 0..3 derive four distinct UUIDs (…10, …11, …12, …13). Exactly 16
// bytes: toDomainDevice rejects any other length outright.
export const DEFAULT_DEVICE_ID = Uint8Array.of(
  0x53, 0x4e, 0x41, 0x50, 0x53, 0x48, 0x4f, 0x54, 0x50, 0x42, 0x00, 0x01, 0x00, 0x00, 0x00, 0x10
);

// Wire values taken from the generated enums, so a renumbering cannot drift.
export const FIRE_MODE = snapshotpb.autococker.v1.AutocockerFireMode;
export const EYE_MODE = snapshotpb.common.v1.EyeMode;
export const BOARD_MODEL = snapshotpb.v1.BoardModel;
export const LANGUAGE = snapshotpb.v1.Language;
export const SCREEN_BRIGHTNESS = snapshotpb.v1.ScreenBrightness;
export const BATTERY_TYPE = snapshotpb.v1.BatteryType;

/**
 * A Profile fixture, using the generated bindings' JS property names.
 *
 * Values are chosen to survive OtsModelMapper.clampProfileToFirmwareRanges()
 * unchanged, so a read/write round trip is a fixed point: the name is ASCII and
 * at most PROFILE_NAME_MAX_BYTES (10), eyeThreshold is within the 0-100 percent
 * scale, and the "0 selects the firmware default" fields are all nonzero.
 */
export function createProfile(overrides = {}) {
  const autococker = {
    fireMode: FIRE_MODE.AUTOCOCKER_FIRE_MODE_SEMI,
    eyeMode: EYE_MODE.EYE_MODE_REFLECTIVE,
    eyeThreshold: 60,
    eyeDebounce: 8,
    searSolenoidDwell: 55,
    cockingSolenoidDelay: 15,
    cockingSolenoidDwellOpen: 25,
    cockingSolenoidDwellClosed: 35,
    rampingCustomMultiplier: 20,
    rampingCustomTriggerPulls: 4,
    rampingCustomRofMinimum: 60,
    triggerDebounceOn: 4,
    triggerDebounceOff: 4,
    cockingSolenoidTimeout: 5000,
    ...(overrides.autococker || {})
  };
  return {
    name: 'Semi Auto',
    // TENTHS of a ball per second: 105 is 10.5 BPS, the NXL 2026 ramping limit.
    fireRateCap: 105,
    // Board-owned: the watch never models it, and a write carries it forward
    // from the last read rather than resetting it.
    boardAutoOff: 15,
    ...overrides,
    autococker
  };
}

/**
 * A board's persisted state, keyed by OTS object. Each value is exactly the
 * properties object the generated encoder consumes, so state and wire format
 * never diverge.
 */
export function createBoardState(overrides = {}) {
  return {
    // Read-only to clients (device.proto).
    device: {
      deviceId: DEFAULT_DEVICE_ID.slice(),
      lockoutEnabled: false,
      model: BOARD_MODEL.BOARD_MODEL_AUTOCOCKER,
      supportedProfileCount: 4,
      ...(overrides.device || {})
    },
    // Client-writable (device_config.proto). active_profile is 1-based; 0 = unset.
    deviceConfig: {
      activeProfile: 1,
      bootText: 'SNAPSHOT',
      // Board-owned settings the watch can leave alone. display_window is never
      // written by the watch at all; the other three are omitted whenever the
      // watch holds no value for them.
      displayWindow: { x: 0, y: 3, w: 110, h: 26 },
      language: LANGUAGE.LANGUAGE_EN,
      screenBrightness: SCREEN_BRIGHTNESS.SCREEN_BRIGHTNESS_DEFAULT,
      screenOffDelay: 5,
      // The watch has no battery setting, and it never writes one. The board holds a 2S LiPo
      // pack, and not the 9 V default, so a write that sends a concrete type fails a test.
      batteryType: BATTERY_TYPE.BATTERY_TYPE_2S_LIPO,
      ...(overrides.deviceConfig || {})
    },
    // Client-writable (profile.proto).
    profiles: overrides.profiles || {
      profiles: [
        createProfile({ name: 'Semi Auto' }),
        createProfile({
          name: 'Ramp',
          fireRateCap: 125,
          autococker: {
            fireMode: FIRE_MODE.AUTOCOCKER_FIRE_MODE_RAMPING_NXL_2026,
            eyeMode: EYE_MODE.EYE_MODE_BREAK_BEAM
          }
        })
      ]
    }
  };
}

/** Encodes one OTS object from board state. Generic across every message type. */
export function encodeObject(state, descriptor) {
  return descriptor.type.encode(descriptor.type.create(state[descriptor.key])).finish();
}

// Plain JS out of a decoded message, with proto3 defaults materialised so the
// stored state always has every field the schema declares.
const TO_OBJECT_OPTIONS = {
  defaults: true,
  arrays: true,
  objects: true,
  bytes: Uint8Array,
  enums: Number,
  longs: Number
};

// Presence-aware view of the same message: a field the client left at its zero
// value is ABSENT here, which on the wire is exactly what "said nothing" looks
// like for a proto3 scalar.
const DELTA_OPTIONS = { ...TO_OBJECT_OPTIONS, defaults: false };

/**
 * Applies a client write to one OTS object.
 *
 * Objects are bare messages, so — unlike the old enveloped format — the board
 * cannot tell a Profiles written into the device_config slot from a valid
 * DeviceConfig; it decodes whatever arrives as the selected object's type,
 * exactly as the firmware does. Only undecodable bytes are an error.
 *
 * Fields listed in the descriptor's `mergeOnZero` keep the board's current value
 * when the client sent nothing for them; everything else is replaced.
 */
export function applyObjectWrite(state, descriptor, bytes) {
  const decoded = descriptor.type.decode(bytes);
  const full = descriptor.type.toObject(decoded, TO_OBJECT_OPTIONS);

  if (!descriptor.mergeOnZero) {
    state[descriptor.key] = full;
    return null;
  }

  const delta = descriptor.type.toObject(decoded, DELTA_OPTIONS);
  const current = state[descriptor.key] || {};
  const next = {};
  for (const field of Object.keys(full)) {
    if (!descriptor.mergeOnZero.includes(field)) {
      next[field] = full[field];
    } else if (delta[field] !== undefined && delta[field] !== null) {
      next[field] = clampField(descriptor, field, delta[field]);
    } else if (current[field] !== undefined) {
      next[field] = current[field]; // the client said nothing: keep what we hold
    } else {
      next[field] = full[field];
    }
  }
  state[descriptor.key] = next;
  return null;
}

function clampField(descriptor, field, value) {
  const max = descriptor.clampMax && descriptor.clampMax[field];
  return typeof max === 'number' && typeof value === 'number' ? Math.min(value, max) : value;
}

/**
 * Firmware stores each object in a fixed-size flash slot and transfers the whole
 * slot, so a read comes back zero-padded past the end of the message. This is
 * what OtsModelMapper.trimProtobufPadding() exists to undo; padding here keeps
 * that workaround under test.
 */
export function padObject(bytes, alignment) {
  if (!alignment || alignment <= 1) return bytes;
  const padded = new Uint8Array(Math.ceil((bytes.length + 1) / alignment) * alignment);
  padded.set(bytes, 0);
  return padded;
}
