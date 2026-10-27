import {
  COCKING_TIMEOUT_DEFAULT,
  COUNT_MAX,
  EYE_DEBOUNCE_DEFAULT,
  EYE_THRESHOLD_MAX,
  EyeMode,
  FireMode,
  MarkerBoltMode,
  NXL_2026_RATE_CAP,
  RATE_CAP_MAX,
  RATE_CAP_MIN,
  TENTHS_MS_MAX
} from '../protocol/OtsModelMapper';

// The numeric profile fields the watch can step, one entry per field. This is a hand-written
// mirror of MarkerProfileField in core:model: the same names, the same order, the same units, the
// same limits, and the same visibility rules. The limits come from the constants that
// OtsModelMapper exports, so only the table itself is a copy. Keep the two in step.
//
// Two visibility rules are the rules of the board menu. The ramping fields show only in the
// custom ramping mode, because no other mode reads them. The fire-rate cap does not show in the
// NXL 2026 mode, because the league sets the cap of that mode. A hidden field keeps its stored
// value, and a write still sends it.
//
// The fine step is always one stored unit, so the stepper can reach every value the phone can
// type. The coarse step is COARSE_STEP_FACTOR fine steps.
export const COARSE_STEP_FACTOR = 5;

// How a field stores its value. A tenths unit shows with one decimal. TENTHS_MULTIPLE is the
// percent divided by ten of the protocol: 25 is 250 percent, which shows as 2.5 times (2.5×).
export const ProfileFieldUnit = {
  TENTHS_BPS: 'TENTHS_BPS',
  BPS: 'BPS',
  PERCENT: 'PERCENT',
  TENTHS_MS: 'TENTHS_MS',
  TENTHS_MULTIPLE: 'TENTHS_MULTIPLE',
  COUNT: 'COUNT'
};

// Tenths of a millisecond in one second, used to turn a cycle time into balls per second.
const TENTHS_MS_PER_SECOND = 10000;

// The fire-rate cap counts tenths of a ball per second. Divide by this for whole balls per second.
const TENTHS_PER_BALL = 10;

// An unknown eye mode counts as on, as in MarkerProfileField: the editor still shows the eye values.
function eyeIsOn(profile) {
  return profile.eyeMode != null && profile.eyeMode !== EyeMode.OFF;
}

// Whether the fire mode has an eye-gated cocking phase, where the firmware consults the cocking
// timeout. Mirrors MarkerFireMode.usesCockingTimeout in core:model.
export function usesCockingTimeout(fireMode) {
  return fireMode === FireMode.SEMI ||
    fireMode === FireMode.FULL_AUTO ||
    fireMode === FireMode.RAMPING_NXL_2026 ||
    fireMode === FireMode.CUSTOM_RAMPING;
}

// Whether the fire mode runs a timed/eye-gated cocking cycle where BoltMode applies. Only SEMI,
// FULL_AUTO, RAMPING_NXL_2026, and CUSTOM_RAMPING do — MECHANICAL holds the breach open via the
// trigger and TRIGGER_ONLY has no cocking phase — so the bolt-mode and sear-delay editors are
// hidden for the others. Mirrors MarkerFireMode.usesBoltMode in core:model.
export function usesBoltMode(fireMode) {
  return fireMode === FireMode.SEMI ||
    fireMode === FireMode.FULL_AUTO ||
    fireMode === FireMode.RAMPING_NXL_2026 ||
    fireMode === FireMode.CUSTOM_RAMPING;
}

// Whether the board runs the open-bolt firing order for [profile] and [boltMode]: the device's
// boltMode is MarkerBoltMode.OPEN and fireMode uses a timed cocking cycle (usesBoltMode). In
// MECHANICAL, TRIGGER_ONLY, and UNKNOWN, open-bolt mode does not apply even when the board is set
// to MarkerBoltMode.OPEN. Mirrors MarkerProfile.usesOpenBolt(boltMode) in core:model.
export function usesOpenBolt(profile, boltMode = MarkerBoltMode.CLOSED) {
  const effectiveBoltMode = boltMode || (profile && profile.boltMode) || MarkerBoltMode.CLOSED;
  return Boolean(profile && effectiveBoltMode === MarkerBoltMode.OPEN && usesBoltMode(profile.fireMode));
}

// Whether the league sets the rate cap of the fire mode. Only the NXL 2026 mode has such a cap.
// The board does not read the fire-rate cap of the profile in that mode, so the editor hides the
// cap. The stored cap stays, and it applies again in a different mode. This is the
// rate_cap_fixed rule of the board menu. Mirrors MarkerFireMode.hasFixedRateCap in core:model.
export function hasFixedRateCap(fireMode) {
  return fireMode === FireMode.RAMPING_NXL_2026;
}

// Whether the fire mode reads the three rampingCustom* values. Only the custom ramping mode does,
// so the editor hides the ramping fields for each other mode. This is the opposite of the
// custom_ramping_off rule of the board menu. Mirrors MarkerFireMode.usesCustomRamping.
export function usesCustomRamping(fireMode) {
  return fireMode === FireMode.CUSTOM_RAMPING;
}

// Whether the board closes the breach on the eye and not on the dwell open. The eye must be on and
// the fire mode must have an eye-gated cocking phase. Mirrors MarkerProfile.eyeClosesBreach.
function eyeClosesBreach(profile) {
  return eyeIsOn(profile) && usesCockingTimeout(profile.fireMode);
}

// The firmware uses its default in place of a stored 0 for these two fields.
function valueOrDefault(value, fallback) {
  const n = toInteger(value);
  return n <= 0 ? fallback : n;
}

// The shortest breach-open phase in tenths of a millisecond. When the eye closes the breach, the
// phase ends when the eye sees a ball for the full eye debounce, or at the anti-jam timeout if
// that comes first. In the other cases it is the dwell open. Mirrors
// MarkerProfile.cockingOpenTenthsMs.
export function cockingOpenTenthsMs(profile) {
  if (!eyeClosesBreach(profile)) {
    return toInteger(profile.cockingSolenoidDwellOpen);
  }
  return Math.min(
    valueOrDefault(profile.eyeDebounce, EYE_DEBOUNCE_DEFAULT),
    valueOrDefault(profile.cockingSolenoidTimeout, COCKING_TIMEOUT_DEFAULT)
  );
}

function isCustomRamping(profile) {
  return usesCustomRamping(profile.fireMode);
}

function rateCapIsAdjustable(profile) {
  return !hasFixedRateCap(profile.fireMode);
}

function always() {
  return true;
}

// `key` is the MarkerProfileField entry name. `field` is the profile key the value lives under.
// `label` matches the KMP profile_label_* English string. `affectsMaxBps` marks the fields that
// enter maxBpsForTiming, so the field page can show the effect of a change.
export const PROFILE_FIELDS = [
  {
    key: 'FIRE_RATE_CAP',
    field: 'fireRateCapTenths',
    label: 'Fire Rate Cap',
    unit: ProfileFieldUnit.TENTHS_BPS,
    min: 0,
    max: RATE_CAP_MAX,
    affectsMaxBps: true,
    isShownFor: rateCapIsAdjustable
  },
  {
    key: 'EYE_THRESHOLD',
    field: 'eyeThreshold',
    label: 'Eye Threshold',
    unit: ProfileFieldUnit.PERCENT,
    min: 1,
    max: EYE_THRESHOLD_MAX,
    affectsMaxBps: false,
    isShownFor: eyeIsOn
  },
  {
    key: 'EYE_DEBOUNCE',
    field: 'eyeDebounce',
    label: 'Eye Debounce',
    unit: ProfileFieldUnit.TENTHS_MS,
    min: 0,
    max: TENTHS_MS_MAX,
    affectsMaxBps: true,
    isShownFor: eyeIsOn
  },
  {
    key: 'COCKING_SOLENOID_TIMEOUT',
    field: 'cockingSolenoidTimeout',
    label: 'Cocking Solenoid Timeout',
    unit: ProfileFieldUnit.TENTHS_MS,
    min: 1,
    max: TENTHS_MS_MAX,
    // A timeout shorter than the eye debounce ends the breach-open phase first.
    affectsMaxBps: true,
    isShownFor: eyeClosesBreach
  },
  {
    key: 'SEAR_SOLENOID_DWELL',
    field: 'searSolenoidDwell',
    label: 'Sear Solenoid Dwell',
    unit: ProfileFieldUnit.TENTHS_MS,
    min: 0,
    max: TENTHS_MS_MAX,
    affectsMaxBps: true,
    isShownFor: always
  },
  {
    key: 'SEAR_SOLENOID_DELAY',
    field: 'searSolenoidDelay',
    label: 'Sear Solenoid Delay',
    unit: ProfileFieldUnit.TENTHS_MS,
    min: 0,
    max: TENTHS_MS_MAX,
    affectsMaxBps: true,
    isShownFor: (p, boltMode) => usesOpenBolt(p, boltMode)
  },
  {
    key: 'COCKING_SOLENOID_DELAY',
    field: 'cockingSolenoidDelay',
    label: 'Cocking Solenoid Delay',
    unit: ProfileFieldUnit.TENTHS_MS,
    min: 0,
    max: TENTHS_MS_MAX,
    affectsMaxBps: true,
    isShownFor: (p, boltMode) => !usesOpenBolt(p, boltMode)
  },
  {
    key: 'COCKING_SOLENOID_DWELL_OPEN',
    field: 'cockingSolenoidDwellOpen',
    label: 'Cocking Solenoid Dwell Open',
    unit: ProfileFieldUnit.TENTHS_MS,
    min: 0,
    max: TENTHS_MS_MAX,
    affectsMaxBps: true,
    isShownFor: always
  },
  {
    key: 'COCKING_SOLENOID_DWELL_CLOSED',
    field: 'cockingSolenoidDwellClosed',
    label: 'Cocking Solenoid Dwell Closed',
    unit: ProfileFieldUnit.TENTHS_MS,
    min: 0,
    max: TENTHS_MS_MAX,
    affectsMaxBps: true,
    isShownFor: always
  },
  {
    key: 'TRIGGER_DEBOUNCE_ON',
    field: 'triggerDebounceOn',
    label: 'Trigger Debounce On',
    unit: ProfileFieldUnit.TENTHS_MS,
    min: 1,
    max: TENTHS_MS_MAX,
    affectsMaxBps: false,
    isShownFor: always
  },
  {
    key: 'TRIGGER_DEBOUNCE_OFF',
    field: 'triggerDebounceOff',
    label: 'Trigger Debounce Off',
    unit: ProfileFieldUnit.TENTHS_MS,
    min: 1,
    max: TENTHS_MS_MAX,
    affectsMaxBps: false,
    isShownFor: always
  },
  {
    key: 'RAMPING_CUSTOM_MULTIPLIER',
    field: 'rampingCustomMultiplier',
    label: 'Ramping Custom Multiplier',
    unit: ProfileFieldUnit.TENTHS_MULTIPLE,
    min: 0,
    max: TENTHS_MS_MAX,
    affectsMaxBps: false,
    isShownFor: isCustomRamping
  },
  {
    key: 'RAMPING_CUSTOM_TRIGGER_PULLS',
    field: 'rampingCustomTriggerPulls',
    label: 'Ramping Custom Trigger Pulls',
    unit: ProfileFieldUnit.COUNT,
    min: 0,
    max: COUNT_MAX,
    affectsMaxBps: false,
    isShownFor: isCustomRamping
  },
  {
    key: 'RAMPING_CUSTOM_ROF_MINIMUM',
    field: 'rampingCustomROFMinimum',
    label: 'Ramping Custom ROF Minimum',
    unit: ProfileFieldUnit.BPS,
    min: 0,
    max: COUNT_MAX,
    affectsMaxBps: false,
    isShownFor: isCustomRamping
  }
];

// The table entry for one MarkerProfileField name, or null for a name the watch does not know.
export function profileFieldByKey(key) {
  return PROFILE_FIELDS.find(f => f.key === key) || null;
}

// Whether an editor shows [field] (a PROFILE_FIELDS entry or its key) for [profile] and [boltMode].
export function isFieldShown(field, profile, boltMode = MarkerBoltMode.CLOSED) {
  const def = typeof field === 'string' ? profileFieldByKey(field) : field;
  return Boolean(def && def.isShownFor(profile, boltMode || MarkerBoltMode.CLOSED));
}

// The fields the editor shows for [profile] and [boltMode], in table order.
export function visibleProfileFields(profile, boltMode = MarkerBoltMode.CLOSED) {
  const mode = boltMode || MarkerBoltMode.CLOSED;
  return PROFILE_FIELDS.filter(f => f.isShownFor(profile, mode));
}

// The name of a fire mode or an eye mode that the board reports and the app does not know. It
// matches the KMP profile_mode_unknown string.
const UNKNOWN_MODE_NAME = 'Unknown Mode';

// The two list-valued profile fields. They reuse the selection page that the board settings use,
// so they carry the same shape as a BOARD_SETTINGS entry. The names match the KMP
// profile_fire_mode_* and profile_eye_mode_* strings. The options leave out UNKNOWN, because the
// user cannot select it: it only names a mode that the board reports.
export const PROFILE_CHOICES = {
  fireMode: {
    title: 'Fire Mode',
    field: 'fireMode',
    options: Object.values(FireMode).filter(mode => mode !== FireMode.UNKNOWN),
    names: {
      [FireMode.MECHANICAL]: 'Mechanical',
      [FireMode.SEMI]: 'Semi',
      [FireMode.TRIGGER_ONLY]: 'Trigger Only',
      [FireMode.FULL_AUTO]: 'Full Auto',
      [FireMode.RAMPING_NXL_2026]: 'NXL 2026',
      [FireMode.CUSTOM_RAMPING]: 'Custom Ramping',
      [FireMode.UNKNOWN]: UNKNOWN_MODE_NAME
    }
  },
  eyeMode: {
    title: 'Eye Mode',
    field: 'eyeMode',
    options: Object.values(EyeMode).filter(mode => mode !== EyeMode.UNKNOWN),
    names: {
      [EyeMode.OFF]: 'Off',
      [EyeMode.REFLECTIVE]: 'Reflective',
      [EyeMode.BREAK_BEAM]: 'Break Beam',
      [EyeMode.UNKNOWN]: UNKNOWN_MODE_NAME
    }
  }
};

// The name the watch shows for one value of a list-valued profile field. A value the app does not
// know shows as itself rather than as nothing.
export function profileChoiceLabel(choiceKey, value) {
  if (value == null) return '';
  const choice = PROFILE_CHOICES[choiceKey];
  if (!choice) return String(value);
  return choice.names[value] || String(value);
}

function toInteger(value) {
  const n = Number(value);
  return Number.isFinite(n) ? Math.trunc(n) : 0;
}

// The value after a step of [delta] stored units from [value], bounded to the field limits.
// Mirrors MarkerProfileField.step in core:model.
//
// The fire-rate cap jumps between 0 (uncapped) and RATE_CAP_MIN. The firmware raises 1 to 9 to
// 10, so a stepper has no reason to visit them: a step down from 10 lands on 0, a step down that
// would pass 10 stops at 10, and a step up from 0 lands on 10.
export function stepProfileField(fieldDef, value, delta) {
  const current = toInteger(value);
  const step = toInteger(delta);
  const next = current + step;
  if (fieldDef.key === 'FIRE_RATE_CAP') {
    if (next <= 0) return 0;
    if (next >= RATE_CAP_MIN) return Math.min(RATE_CAP_MAX, next);
    if (step > 0) return RATE_CAP_MIN;
    return current > RATE_CAP_MIN ? RATE_CAP_MIN : 0;
  }
  return Math.min(fieldDef.max, Math.max(fieldDef.min, next));
}

// The step for one button: the fine step is one unit, the coarse step is COARSE_STEP_FACTOR units.
export function stepSize(coarse) {
  return coarse ? COARSE_STEP_FACTOR : 1;
}

// The text for a step button of [delta] stored units, in the unit the field shows.
export function stepLabel(fieldDef, delta) {
  const sign = delta < 0 ? '-' : '+';
  const size = Math.abs(delta);
  switch (fieldDef.unit) {
    case ProfileFieldUnit.TENTHS_MS:
    case ProfileFieldUnit.TENTHS_BPS:
    case ProfileFieldUnit.TENTHS_MULTIPLE:
      return `${sign}${(size / 10).toFixed(1)}`;
    default:
      return `${sign}${size}`;
  }
}

// The text the watch shows for one value of a numeric field. Milliseconds, the fire-rate cap and
// the multiple show with one decimal; a cap of 0 shows as uncapped. The strings match the KMP
// profile_value_* English strings.
export function formatProfileFieldValue(fieldDef, value) {
  const n = toInteger(value);
  switch (fieldDef.unit) {
    case ProfileFieldUnit.TENTHS_MS:
      return `${(n / 10).toFixed(1)} ms`;
    case ProfileFieldUnit.TENTHS_BPS:
      return n <= 0 ? 'Uncapped' : `${(n / 10).toFixed(1)} BPS`;
    case ProfileFieldUnit.BPS:
      return `${n} BPS`;
    case ProfileFieldUnit.TENTHS_MULTIPLE:
      return `${(n / 10).toFixed(1)}×`;
    case ProfileFieldUnit.PERCENT:
      return `${n}%`;
    default:
      return String(n);
  }
}

// The fire-rate cap that the board applies to [profile], in tenths of a ball per second. 0 is
// uncapped. In the NXL 2026 fire mode the board uses the league limit and does not read the cap
// of the profile. Mirrors MarkerProfile.effectiveFireRateCap in core:model.
export function effectiveFireRateCapTenths(profile) {
  return hasFixedRateCap(profile.fireMode)
    ? NXL_2026_RATE_CAP
    : toInteger(profile.fireRateCapTenths);
}

// The timing-limited maximum balls per second for [profile], bounded by the fire-rate cap that
// the board applies, with one decimal. Mirrors MarkerProfile.maxBpsForTiming in core:model.
//
// In closed-bolt mode (or fire modes where open-bolt does not apply), the firing cycle is the sum
// of the cocking delay, the shortest breach-open phase and the dwell closed. In open-bolt mode,
// the cocking sequence (open + closed) and the sear sequence (sear delay + sear dwell) run in
// parallel from trigger_debounce_on, so the longer sequence bounds the cycle. Mirrors
// MarkerProfile.cycleTenthsMs.
export function maxBpsForTiming(profile, boltMode = MarkerBoltMode.CLOSED) {
  const closed = toInteger(profile.cockingSolenoidDwellClosed);
  let cycleTenthsMs;
  if (usesOpenBolt(profile, boltMode)) {
    const searDelay = toInteger(profile.searSolenoidDelay);
    const searDwell = toInteger(profile.searSolenoidDwell);
    cycleTenthsMs = Math.max(cockingOpenTenthsMs(profile) + closed, searDelay + searDwell);
  } else {
    const delay = toInteger(profile.cockingSolenoidDelay);
    cycleTenthsMs = delay + cockingOpenTenthsMs(profile) + closed;
  }
  const timingBps = cycleTenthsMs > 0 ? TENTHS_MS_PER_SECOND / cycleTenthsMs : 0;
  const capTenths = effectiveFireRateCapTenths(profile);
  const cap = capTenths <= 0 ? timingBps : capTenths / TENTHS_PER_BALL;
  return Math.min(timingBps, cap).toFixed(1);
}
