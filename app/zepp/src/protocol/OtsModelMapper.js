/** Largest shift a 32-bit length varint can use before JS bitwise ops overflow into the sign bit. */
const MAX_VARINT_SHIFT = 28;

export function trimProtobufPadding(bytes) {
  let pos = 0;
  const len = bytes.length;
  while (pos < len) {
    if (bytes[pos] === 0) {
      return bytes.slice(0, pos);
    }
    const tagPos = pos;
    let tag = 0;
    let shift = 0;
    let ok = false;
    while (pos < len) {
      const b = bytes[pos++];
      tag = tag | ((b & 0x7f) << shift);
      if ((b & 0x80) === 0) {
        ok = true;
        break;
      }
      shift += 7;
    }
    if (!ok || tag === 0) {
      return bytes.slice(0, tagPos);
    }
    const wireType = tag & 0x07;
    switch (wireType) {
      case 0: { // varint
        let varintOk = false;
        while (pos < len) {
          const b = bytes[pos++];
          if ((b & 0x80) === 0) {
            varintOk = true;
            break;
          }
        }
        if (!varintOk) return bytes.slice(0, tagPos);
        break;
      }
      case 1: { // 64-bit
        if (pos + 8 > len) return bytes.slice(0, tagPos);
        pos += 8;
        break;
      }
      case 2: { // length-delimited
        // Bound the shift and reject a negative result: JS bitwise ops are 32-bit signed, so a
        // corrupt length reaching bit 31 goes negative, passes the `pos + length > len` guard, and
        // walks `pos` backwards. Mirrors the Kotlin trimProtobufPadding.
        let length = 0;
        let lengthShift = 0;
        let lenOk = false;
        while (pos < len && lengthShift <= MAX_VARINT_SHIFT) {
          const b = bytes[pos++];
          length = length | ((b & 0x7f) << lengthShift);
          if ((b & 0x80) === 0) {
            lenOk = true;
            break;
          }
          lengthShift += 7;
        }
        if (!lenOk || length < 0 || pos + length > len) return bytes.slice(0, tagPos);
        pos += length;
        break;
      }
      case 5: { // 32-bit
        if (pos + 4 > len) return bytes.slice(0, tagPos);
        pos += 4;
        break;
      }
      default: {
        return bytes.slice(0, tagPos);
      }
    }
  }
  return bytes;
}

export function bytesToUuidString(bytes) {
  const hex = Array.from(bytes, b => b.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

export function getProfileUuid(deviceUuidBytes, slotIndex) {
  const bytes = new Uint8Array(deviceUuidBytes);
  bytes[15] = bytes[15] ^ slotIndex;
  return bytesToUuidString(bytes);
}

/**
 * Firmware-enforced limits, mirrored from core/model/DeviceInputLimits.kt and Device.kt so the two
 * clients cannot drift. Keep these in sync with the Kotlin constants of the same name.
 *
 * PROFILE_NAME_MAX_BYTES is 10, NOT 11: the firmware stores the name in a nanopb `char[11]` buffer
 * and nanopb reserves one byte for the NUL terminator, so an 11-byte payload overflows and the
 * WHOLE Profiles message fails to decode on the board. Boot text is 10 for the same reason
 * (`char[11]`).
 */
export const PROFILE_NAME_MAX_BYTES = 10;
export const BOOT_TEXT_MAX_BYTES = 10;
export const TENTHS_MS_MAX = 99999;
export const COUNT_MAX = 9999;
export const EYE_THRESHOLD_MAX = 100;
export const EYE_THRESHOLD_DEFAULT = 50;
// fire_rate_cap counts TENTHS of a ball per second, so 105 is 10.5 BPS. When capped, it clamps
// to 10-1000 (1.0-100.0 BPS).
export const RATE_CAP_MIN = 10;
export const RATE_CAP_MAX = 1000;
// The rate cap of the NXL 2026 fire mode: 10.5 BPS, which is one ball in 95 ms. The league sets
// it. In that mode the board uses this cap and does not read fire_rate_cap. Mirrors
// NXL_2026_RATE_CAP in core:model.
export const NXL_2026_RATE_CAP = 105;
// The factory default that the board materializes for a fresh profile: the NXL 2026 limit.
export const FIRE_RATE_CAP_DEFAULT = NXL_2026_RATE_CAP;
export const TRIGGER_DEBOUNCE_DEFAULT = 20;
export const COCKING_TIMEOUT_DEFAULT = 5000;
// The firmware uses this eye debounce (2.0 ms) in place of a stored 0.
export const EYE_DEBOUNCE_DEFAULT = 20;

// The screen-off delay clamps to 1-999 seconds. On the wire 0 means "leave the board's delay
// alone", so the app model carries null for "not reported" and never sends a 0 it chose.
export const SCREEN_OFF_DELAY_MIN = 1;
export const SCREEN_OFF_DELAY_MAX = 999;
export const SCREEN_OFF_DELAY_DEFAULT = 5;

function truncateUtf8(str, maxBytes) {
  const s = str || '';
  if (typeof TextEncoder === 'undefined') {
    return s.slice(0, maxBytes); // Fallback if no encoder available
  }
  const enc = new TextEncoder();
  if (enc.encode(s).length <= maxBytes) return s;
  let out = '';
  for (const ch of s) {
    if (enc.encode(out + ch).length > maxBytes) break;
    out += ch;
  }
  return out;
}

// UNKNOWN is a fire mode or an eye mode that the board reports as 0 (UNSPECIFIED) or as a number
// this app does not know. The editor shows it as unknown and does not offer it. A write sends the
// board's own number back until the user selects a mode. Mirrors MarkerFireMode.UNKNOWN and
// MarkerEyeMode.UNKNOWN in core:model.
//
// RAMPING_NXL_2026 is ramping with the rules of the league: the user cannot adjust a rule of it.
// Its stored value is 'RAMPING_CUSTOM', as in core:model. It was the one ramping mode of the app
// before the protocol had a custom ramping mode, and its name then was RAMPING_CUSTOM. A stored
// profile holds that value for this mode, so the value must keep this meaning.
//
// CUSTOM_RAMPING is ramping with rules that the user sets. Only this mode reads the three
// rampingCustom* values. Do not confuse its stored value with the stored value of the NXL mode.
export const FireMode = {
  MECHANICAL: 'MECHANICAL',
  SEMI: 'SEMI',
  TRIGGER_ONLY: 'TRIGGER_ONLY',
  FULL_AUTO: 'FULL_AUTO',
  RAMPING_NXL_2026: 'RAMPING_CUSTOM',
  CUSTOM_RAMPING: 'CUSTOM_RAMPING',
  UNKNOWN: 'UNKNOWN'
};

export const EyeMode = {
  OFF: 'OFF',
  REFLECTIVE: 'REFLECTIVE',
  BREAK_BEAM: 'BREAK_BEAM',
  UNKNOWN: 'UNKNOWN'
};

export const BoardModel = {
  UNKNOWN: 'UNKNOWN',
  AUTOCOCKER: 'AUTOCOCKER'
};

// Language of the board's own menu text (proto Language). The values are the KMP BoardLanguage
// names, so both clients mean the same language. A device holds null when the board has not
// reported a language: firmware from before the field omits it, which decodes as 0 (UNSPECIFIED).
export const BoardLanguage = {
  EN: 'EN',
  ES: 'ES',
  DE: 'DE',
  FR: 'FR'
};

// How bright the board drives its screen (proto ScreenBrightness). The values are the KMP
// BoardScreenBrightness names. It is a BOARD setting and not a profile setting: one screen serves
// every profile slot. The protocol held it on Profile tag 5 until it moved to DeviceConfig.
// A device holds null when the board has not reported one.
export const BoardScreenBrightness = {
  LOW: 'LOW',
  DEFAULT: 'DEFAULT',
  HIGH: 'HIGH'
};

// Proto enums now carry a 0 = UNSPECIFIED value, so wire values are shifted by +1
// relative to the old schema. The numbers are those of AutocockerFireMode in fire_mode.proto.
export function fireModeToDomain(protoVal) {
  switch (protoVal) {
    case 1: return FireMode.MECHANICAL;
    case 2: return FireMode.SEMI;
    case 3: return FireMode.TRIGGER_ONLY;
    case 4: return FireMode.FULL_AUTO;
    case 5: return FireMode.RAMPING_NXL_2026;
    case 6: return FireMode.CUSTOM_RAMPING;
    default: return FireMode.UNKNOWN; // incl. 0 (UNSPECIFIED); matches Kotlin toDomain()
  }
}

// UNKNOWN goes out as 0 (UNSPECIFIED). toProtocolConfig replaces it with the board's own number
// when it has a last-read slot.
export function fireModeToProto(domainVal) {
  switch (domainVal) {
    case FireMode.MECHANICAL: return 1;
    case FireMode.SEMI: return 2;
    case FireMode.TRIGGER_ONLY: return 3;
    case FireMode.FULL_AUTO: return 4;
    case FireMode.RAMPING_NXL_2026: return 5;
    case FireMode.CUSTOM_RAMPING: return 6;
    case FireMode.UNKNOWN: return 0;
    default: return 2; // SEMI
  }
}

export function eyeModeToDomain(protoVal) {
  switch (protoVal) {
    case 1: return EyeMode.OFF;
    case 2: return EyeMode.REFLECTIVE;
    case 3: return EyeMode.BREAK_BEAM;
    default: return EyeMode.UNKNOWN; // incl. 0 (UNSPECIFIED); matches Kotlin toDomain()
  }
}

export function eyeModeToProto(domainVal) {
  switch (domainVal) {
    case EyeMode.OFF: return 1;
    case EyeMode.REFLECTIVE: return 2;
    case EyeMode.BREAK_BEAM: return 3;
    case EyeMode.UNKNOWN: return 0;
    default: return 1; // OFF
  }
}

// A value this app does not know (a newer firmware language) also maps to null, so the watch never
// shows or writes a language it cannot name. Mirrors the KMP decodeDeviceConfig and
// Language.toDomain().
export function languageToDomain(protoVal) {
  switch (protoVal) {
    case 1: return BoardLanguage.EN;
    case 2: return BoardLanguage.ES;
    case 3: return BoardLanguage.DE;
    case 4: return BoardLanguage.FR;
    default: return null; // incl. 0 (UNSPECIFIED)
  }
}

export function languageToProto(domainVal) {
  switch (domainVal) {
    case BoardLanguage.EN: return 1;
    case BoardLanguage.ES: return 2;
    case BoardLanguage.DE: return 3;
    case BoardLanguage.FR: return 4;
    default: return 0; // UNSPECIFIED: the board keeps its current language
  }
}

// A value this app does not know (a newer firmware level) also maps to null, so the watch never
// shows or writes a brightness it cannot name. Mirrors the KMP ScreenBrightness.toDomain().
export function screenBrightnessToDomain(protoVal) {
  switch (protoVal) {
    case 1: return BoardScreenBrightness.LOW;
    case 2: return BoardScreenBrightness.DEFAULT;
    case 3: return BoardScreenBrightness.HIGH;
    default: return null; // incl. 0 (UNSPECIFIED)
  }
}

export function screenBrightnessToProto(domainVal) {
  switch (domainVal) {
    case BoardScreenBrightness.LOW: return 1;
    case BoardScreenBrightness.DEFAULT: return 2;
    case BoardScreenBrightness.HIGH: return 3;
    default: return 0; // UNSPECIFIED: the board keeps its current brightness
  }
}

// The screen-off delay is 1-999 seconds. On the wire 0 means that the board reported no delay, so
// it maps to null rather than to a literal zero. Mirrors the KMP screenOffDelayToDomain().
export function screenOffDelayToDomain(protoVal) {
  const seconds = protoVal || 0;
  return seconds > 0 ? seconds : null;
}

export function boardModelToDomain(protoVal) {
  switch (protoVal) {
    case 1: return BoardModel.AUTOCOCKER;
    default: return BoardModel.UNKNOWN; // incl. 0 (UNSPECIFIED)
  }
}

// Maps the read-only Device + DeviceConfig + Profiles messages to the app model.
// active_profile, boot_text, language and the screen settings come from DeviceConfig;
// model/supportedProfileCount come from Device.
export function toDomainDevice(protoDevice, protoDeviceConfig, protoProfiles, deviceName, macAddress) {
  const deviceUuidBytes = protoDevice.deviceId;
  // A short or empty device_id (the proto3 default) would give every such board the same store
  // key and every profile the same id, and the store would merge two boards into one record.
  if (!deviceUuidBytes || deviceUuidBytes.length !== 16) {
    throw new Error(`device_id must be 16 bytes but was ${deviceUuidBytes ? deviceUuidBytes.length : 0}`);
  }
  const deviceId = bytesToUuidString(deviceUuidBytes);

  const mappedProfiles = (protoProfiles.profiles || []).map((profile, index) => {
    const id = getProfileUuid(deviceUuidBytes, index);
    // Firing mechanics now live in the autococker arm of the board_config oneof. A profile
    // without that arm reads as all zeros, as the KMP mapper reads it (AutocockerConfig()), so no
    // field is undefined and fillProfileDefaults() never changes a board-read profile.
    const autococker = profile.autococker || EMPTY_AUTOCOCKER;
    return {
      id: id,
      name: profile.name,
      fireMode: fireModeToDomain(autococker.fireMode),
      fireRateCapTenths: profile.fireRateCap,
      eyeMode: eyeModeToDomain(autococker.eyeMode),
      eyeThreshold: autococker.eyeThreshold,
      eyeDebounce: autococker.eyeDebounce,
      searSolenoidDwell: autococker.searSolenoidDwell,
      cockingSolenoidDelay: autococker.cockingSolenoidDelay,
      cockingSolenoidDwellOpen: autococker.cockingSolenoidDwellOpen,
      cockingSolenoidDwellClosed: autococker.cockingSolenoidDwellClosed,
      rampingCustomMultiplier: autococker.rampingCustomMultiplier,
      rampingCustomTriggerPulls: autococker.rampingCustomTriggerPulls,
      rampingCustomROFMinimum: autococker.rampingCustomRofMinimum,
      triggerDebounceOn: autococker.triggerDebounceOn,
      triggerDebounceOff: autococker.triggerDebounceOff,
      // Anti-jam bound (proto field 14). Mirrors the KMP cockingSolenoidTimeout domain field.
      cockingSolenoidTimeout: autococker.cockingSolenoidTimeout
    };
  });

  // active_profile is a 1-based index (0 = unset); resolve to the matching slot UUID.
  const activeProfileNum = protoDeviceConfig.activeProfile || 0;
  const activeProfileId = (activeProfileNum >= 1 && activeProfileNum <= mappedProfiles.length)
    ? getProfileUuid(deviceUuidBytes, activeProfileNum - 1)
    : null;

  // boot_text may arrive NUL-padded from a fixed-width firmware buffer. Match Kotlin: stop at
  // the first NUL, then fall back to the "SnapshotPB" default when the result is empty (an
  // all-NUL buffer must not decode to an empty string that would falsely diverge from the phone).
  const bootTextStr = ((protoDeviceConfig.bootText || '').split('\0')[0]) || 'SnapshotPB';

  return {
    id: deviceId,
    name: deviceName,
    mac: macAddress,
    lastSeen: new Date().toISOString().split('T')[0],
    knownProfiles: mappedProfiles,
    activeProfileId: activeProfileId,
    bootText: bootTextStr,
    language: languageToDomain(protoDeviceConfig.language),
    screenBrightness: screenBrightnessToDomain(protoDeviceConfig.screenBrightness),
    screenOffDelay: screenOffDelayToDomain(protoDeviceConfig.screenOffDelay),
    lockoutEnabled: protoDevice.lockoutEnabled,
    boardModel: boardModelToDomain(protoDevice.model),
    supportedProfileCount: protoDevice.supportedProfileCount || mappedProfiles.length
  };
}

// The proto3 zero values of AutocockerConfig, for a profile that has no autococker arm.
const EMPTY_AUTOCOCKER = Object.freeze({
  fireMode: 0,
  eyeMode: 0,
  eyeThreshold: 0,
  eyeDebounce: 0,
  searSolenoidDwell: 0,
  cockingSolenoidDelay: 0,
  cockingSolenoidDwellOpen: 0,
  cockingSolenoidDwellClosed: 0,
  rampingCustomMultiplier: 0,
  rampingCustomTriggerPulls: 0,
  rampingCustomRofMinimum: 0,
  triggerDebounceOn: 0,
  triggerDebounceOff: 0,
  cockingSolenoidTimeout: 0
});

// Bounds a value to [min, max], flooring undefined/NaN/negative inputs (a stale-proto
// field reads back undefined, an imported/sync value can be negative) to 0 first, so the
// clamped result is always a valid uint on the wire.
function clampRange(value, min, max) {
  const n = Number(value);
  const v = Number.isFinite(n) ? n : 0;
  return Math.min(max, Math.max(min, v));
}

// For fields whose firmware "0 => select default" behavior would otherwise loop
// (eye_threshold, trigger_debounce_on/off, cocking_solenoid_timeout): substitute a concrete
// nonzero default so the app never emits the 0 sentinel, then clamp into range.
function clampWithDefault(value, defaultValue, min, max) {
  const n = Number(value);
  if (!Number.isFinite(n) || n <= 0) return defaultValue;
  return Math.min(max, Math.max(min, n));
}

// fire_rate_cap keeps 0 as a real "uncapped" value that round-trips; any other value
// clamps to RATE_CAP_MIN-RATE_CAP_MAX tenths of a ball per second (1.0-100.0 BPS).
function clampFireRateCap(value) {
  const n = Number(value);
  if (!Number.isFinite(n) || n <= 0) return 0;
  return Math.min(RATE_CAP_MAX, Math.max(RATE_CAP_MIN, n));
}

// Reproduces the firmware write chokepoint (fire_control_util_normalize_profiles) exactly:
// out-of-range values are bounded, never rejected, and 0 selects the firmware default for
// several fields. Applied to every profile before it goes on the wire so the value the board
// stores equals the value the app sent (no silent-normalization divergence). Mirrors the KMP
// MarkerProfile.clampedToFirmwareRanges().
export function clampProfileToFirmwareRanges(p) {
  return {
    ...p,
    // ASCII-only + 10 chars, matching String.limitProfileName() in core:model. Applying it here as
    // well as at the wire boundary is what lets the STORED copy equal what the board holds.
    name: limitProfileName(p.name),
    fireRateCapTenths: clampFireRateCap(p.fireRateCapTenths),
    eyeThreshold: clampWithDefault(p.eyeThreshold, EYE_THRESHOLD_DEFAULT, 0, EYE_THRESHOLD_MAX),
    triggerDebounceOn: clampWithDefault(p.triggerDebounceOn, TRIGGER_DEBOUNCE_DEFAULT, 0, TENTHS_MS_MAX),
    triggerDebounceOff: clampWithDefault(p.triggerDebounceOff, TRIGGER_DEBOUNCE_DEFAULT, 0, TENTHS_MS_MAX),
    cockingSolenoidTimeout: clampWithDefault(p.cockingSolenoidTimeout, COCKING_TIMEOUT_DEFAULT, 0, TENTHS_MS_MAX),
    eyeDebounce: clampRange(p.eyeDebounce, 0, TENTHS_MS_MAX),
    searSolenoidDwell: clampRange(p.searSolenoidDwell, 0, TENTHS_MS_MAX),
    cockingSolenoidDelay: clampRange(p.cockingSolenoidDelay, 0, TENTHS_MS_MAX),
    cockingSolenoidDwellOpen: clampRange(p.cockingSolenoidDwellOpen, 0, TENTHS_MS_MAX),
    cockingSolenoidDwellClosed: clampRange(p.cockingSolenoidDwellClosed, 0, TENTHS_MS_MAX),
    rampingCustomMultiplier: clampRange(p.rampingCustomMultiplier, 0, TENTHS_MS_MAX),
    rampingCustomTriggerPulls: clampRange(p.rampingCustomTriggerPulls, 0, COUNT_MAX),
    rampingCustomROFMinimum: clampRange(p.rampingCustomROFMinimum, 0, COUNT_MAX)
  };
}

/** ASCII-only, capped at PROFILE_NAME_MAX_BYTES. Mirrors String.limitProfileName() in core:model. */
export function limitProfileName(name) {
  const s = name || '';
  let out = '';
  for (const ch of s) {
    if (ch.codePointAt(0) > 127) continue;
    if (out.length >= PROFILE_NAME_MAX_BYTES) break;
    out += ch;
  }
  return out;
}

/**
 * Resets an out-of-range eyeThreshold to the app default on every stored profile.
 *
 * Firmware a4b1c99 redefined eye_threshold from a raw 0-1000 ADC reading to a 0-100 percent of ADC
 * full scale. The old scale does not map linearly onto the new one, so a stored value above
 * EYE_THRESHOLD_MAX is RESET (not clamped to 100, which would wrongly max out eye sensitivity); the
 * board re-syncs the true percent on the next read. Idempotent, so no schema-version marker is
 * needed. Mirrors DeviceStore.normalizedEyeThreshold() on the Kotlin side, which the watch was
 * missing entirely — a device stored before the rescale otherwise diverged from the board forever.
 */
export function normalizeDeviceForStorage(device) {
  if (!device) return device;
  const next = { ...device, knownProfiles: normalizeProfiles(device.knownProfiles) };
  // The board baseline holds the same profile shape, so it gets the same rules. Without this a
  // baseline and a local copy could differ in a field neither side edited. A device stored
  // before the baseline existed has none, and it stays absent.
  if (device.boardState) {
    next.boardState = {
      ...device.boardState,
      knownProfiles: normalizeProfiles(device.boardState.knownProfiles)
    };
  }
  return next;
}

// The fields of the domain profile, from MarkerProfile in core:model. The two lists below are the
// only hand-written copy on the watch. The divergence check in DeviceStore and the default fill
// both come from them, and profile-fields-parity.test.js compares them with the fixture
// marker-profile-fields.json, which the Kotlin MarkerProfileFixtureTest compares with
// MarkerProfile. So a new MarkerProfile field fails a test until the watch knows it.
//
// The fields that have no default. Every profile carries them.
export const PROFILE_REQUIRED_FIELDS = Object.freeze(['id', 'name']);

// The default of each other profile field, from the MarkerProfile constructor defaults.
// retireLegacyFireRateCap() also sets fireRateCapTenths, for a stored value that is not a number
// or that is negative.
export const PROFILE_FIELD_DEFAULTS = Object.freeze({
  fireMode: FireMode.SEMI,
  fireRateCapTenths: FIRE_RATE_CAP_DEFAULT,
  eyeMode: EyeMode.OFF,
  eyeThreshold: EYE_THRESHOLD_DEFAULT,
  eyeDebounce: EYE_DEBOUNCE_DEFAULT,
  searSolenoidDwell: 45,
  cockingSolenoidDelay: 60,
  cockingSolenoidDwellOpen: 50,
  cockingSolenoidDwellClosed: 300,
  rampingCustomMultiplier: 15,
  rampingCustomTriggerPulls: 3,
  rampingCustomROFMinimum: 3,
  triggerDebounceOn: TRIGGER_DEBOUNCE_DEFAULT,
  triggerDebounceOff: TRIGGER_DEBOUNCE_DEFAULT,
  cockingSolenoidTimeout: COCKING_TIMEOUT_DEFAULT
});

// Every field of the domain profile. The divergence check compares these fields, so a field
// that is not here is one whose board change shows as "Synced", and a "Keep My Values" write
// then overwrites the board value.
export const PROFILE_FIELD_NAMES = Object.freeze([
  ...PROFILE_REQUIRED_FIELDS,
  ...Object.keys(PROFILE_FIELD_DEFAULTS)
]);

// Gives each absent field (undefined or null) its MarkerProfile default. A profile stored before
// a field existed has no value for it, and a missing value never equals the value a board reports,
// so without this the next connect raises a conflict. The KMP decoder fills absent keys with the
// constructor defaults in the same way.
function fillProfileDefaults(profile) {
  const next = { ...profile };
  Object.keys(PROFILE_FIELD_DEFAULTS).forEach((field) => {
    if (next[field] === undefined || next[field] === null) {
      next[field] = PROFILE_FIELD_DEFAULTS[field];
    }
  });
  return next;
}

function normalizeProfiles(profiles) {
  return (profiles || []).map((p) => {
    const filled = fillProfileDefaults(p);
    const next = Number(filled.eyeThreshold) > EYE_THRESHOLD_MAX
      ? { ...filled, eyeThreshold: EYE_THRESHOLD_DEFAULT }
      : filled;
    return retireLegacyFireRateCap(next);
  });
}

// Retires the legacy whole-balls-per-second fire-rate cap. The field counted whole balls per
// second under the key `fireRateCap` until the NXL 2026 ramping limit of 10.5 BPS showed that a
// whole number cannot express a legal rate; it now counts TENTHS of a ball per second under the
// key `fireRateCapTenths`. Old and new values share one range, so nothing can tell a stored 13
// (13 BPS) from a new 13 (1.3 BPS). So the old key is dropped and the default is materialized.
// Mirrors the @SerialName key rename on MarkerProfile in core:model.
//
// The default is a placeholder, not the truth: the next read of a board that holds a different
// cap fails _profilesEqual() and raises a Conflict, and TakeDevice adopts the board's own value.
// TakeLocal writes this 105 back instead, so a watch that migrated a real cap loses it. That is
// the accepted cost of the in-place unit change; profile.proto states it the same way.
//
// The guard rejects anything that is not a real number, because storage is JSON: a string "150"
// is finite once coerced, but it never equals a board-reported 150 under the strict compare in
// _profilesEqual(), so it would raise a Conflict on every reconnect. A negative is no legal cap
// either -- clampFireRateCap() would send it as 0 (uncapped) and diverge the same way.
// Idempotent -- no schema-version marker needed.
function retireLegacyFireRateCap(profile) {
  const next = { ...profile };
  delete next.fireRateCap;
  const tenths = next.fireRateCapTenths;
  if (typeof tenths !== 'number' || !Number.isFinite(tenths) || tenths < 0) {
    next.fireRateCapTenths = FIRE_RATE_CAP_DEFAULT;
  }
  return next;
}

// Encodes the app model into the writable DeviceConfig (active profile, boot text, language and the
// screen settings) and Profiles messages. The read-only Device message is never written by clients.
// display_window is never set: the board keeps its current window when the field is absent.
// battery_type is never set for the same reason. The watch has no battery setting, and an absent
// value leaves the board's own battery type alone. Do not send a concrete default: a write of
// BATTERY_TYPE_9V to a board with a 2S LiPo pack gives the board the voltage thresholds of an
// alkaline cell, and it can discharge the pack too far.
//
// [lastReadProfiles] is the decoded protobuf `Profiles` last read from this board. The proto
// `Profile` carries board-owned per-profile fields the domain model does not model
// (board_auto_off). Because a save rewrites the whole `Profiles` object,
// building each profile fresh would reset those to proto defaults, silently wiping them; so
// each freshly built profile inherits its slot's last-known values (slots align by index).
// Defaults (0 / UNSPECIFIED) are used when no last-read value is available. Mirrors the KMP
// toProtocolProfiles(lastReadProfiles) carry-forward.
export function toProtocolConfig(domainDevice, lastReadProfiles = null) {
  // active_profile is a 1-based index (0 = unset).
  let activeProfile = 0;
  if (domainDevice.activeProfileId) {
    const idx = domainDevice.knownProfiles.findIndex(p => p.id === domainDevice.activeProfileId);
    if (idx >= 0) activeProfile = idx + 1;
  }

  const deviceConfig = {
    activeProfile: activeProfile,
    bootText: truncateUtf8(domainDevice.bootText || 'SnapshotPB', BOOT_TEXT_MAX_BYTES)
  };
  // Leave a setting out when the watch has none for it: an absent field leaves the board's own
  // value alone, and the bytes stay the same as before the field existed.
  const language = languageToProto(domainDevice.language);
  if (language !== 0) deviceConfig.language = language;

  const brightness = screenBrightnessToProto(domainDevice.screenBrightness);
  if (brightness !== 0) deviceConfig.screenBrightness = brightness;

  // The board clamps 1000 or more to 999, so clamp here and send the value the board will store.
  // A value below 1 is no legal delay, so it is left out and the board keeps its own. The read-back
  // after the write is what corrects the stored copy.
  const delay = domainDevice.screenOffDelay || 0;
  if (delay >= SCREEN_OFF_DELAY_MIN) {
    deviceConfig.screenOffDelay = Math.min(delay, SCREEN_OFF_DELAY_MAX);
  }

  const boardProfiles = (lastReadProfiles && lastReadProfiles.profiles) || [];

  const profiles = {
    profiles: domainDevice.knownProfiles.map((rawProfile, index) => {
      const p = clampProfileToFirmwareRanges(rawProfile);
      const board = boardProfiles[index] || {};
      const boardArm = board.autococker || null;
      return {
        name: truncateUtf8(p.name, PROFILE_NAME_MAX_BYTES),
        fireRateCap: p.fireRateCapTenths,
        // Board-owned fields: preserved from the aligned last-read slot (0 / UNSPECIFIED default).
        boardAutoOff: board.boardAutoOff || 0,
        autococker: {
          // An unknown mode keeps the board's own number, so a save never changes it by itself.
          // [lastReadProfiles] changes only on a read. That is safe: the user cannot select
          // UNKNOWN, so a profile that still holds it has a board number that did not change.
          fireMode: p.fireMode === FireMode.UNKNOWN && boardArm
            ? boardArm.fireMode || 0
            : fireModeToProto(p.fireMode),
          eyeMode: p.eyeMode === EyeMode.UNKNOWN && boardArm
            ? boardArm.eyeMode || 0
            : eyeModeToProto(p.eyeMode),
          eyeThreshold: p.eyeThreshold,
          eyeDebounce: p.eyeDebounce,
          searSolenoidDwell: p.searSolenoidDwell,
          cockingSolenoidDelay: p.cockingSolenoidDelay,
          cockingSolenoidDwellOpen: p.cockingSolenoidDwellOpen,
          cockingSolenoidDwellClosed: p.cockingSolenoidDwellClosed,
          rampingCustomMultiplier: p.rampingCustomMultiplier,
          rampingCustomTriggerPulls: p.rampingCustomTriggerPulls,
          rampingCustomRofMinimum: p.rampingCustomROFMinimum,
          triggerDebounceOn: p.triggerDebounceOn,
          triggerDebounceOff: p.triggerDebounceOff,
          cockingSolenoidTimeout: p.cockingSolenoidTimeout
        }
      };
    })
  };

  return { deviceConfig, profiles };
}
