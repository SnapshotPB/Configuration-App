// The max BPS of the watch for each eye mode. Mirrors the maxBpsForTiming tests of MarkerProfile
// in core:model, so the phone and the watch show the same number for one profile.
//
// The firmware arms the cocking-open timer with the dwell open when the eye does not close the
// breach. When the eye closes the breach (an eye sensor and an eye-gated fire mode), it arms the
// anti-jam timeout and closes the breach when the eye sees a ball for the full eye debounce. The
// shortest open phase is then the smaller of the eye debounce and the timeout.

import { describe, expect, test } from 'vitest';
import { EyeMode, FireMode, MarkerBoltMode, NXL_2026_RATE_CAP, RATE_CAP_MAX } from '../../src/protocol/OtsModelMapper.js';
import {
  cockingOpenTenthsMs,
  effectiveFireRateCapTenths,
  maxBpsForTiming,
  usesBoltMode,
  usesOpenBolt
} from '../../src/utils/profileFields.js';

// Uncapped, so the max BPS shows the timing rate. Delay 6.0 ms, open 5.0 ms, closed 30.0 ms.
function timing(overrides) {
  return {
    fireMode: FireMode.SEMI,
    eyeMode: EyeMode.OFF,
    fireRateCapTenths: 0,
    eyeDebounce: 20,
    searSolenoidDwell: 45,
    searSolenoidDelay: 60,
    cockingSolenoidDelay: 60,
    cockingSolenoidDwellOpen: 50,
    cockingSolenoidDwellClosed: 300,
    cockingSolenoidTimeout: 5000,
    ...overrides
  };
}

const SENSOR_EYE_MODES = [EyeMode.REFLECTIVE, EyeMode.BREAK_BEAM];
const EYE_GATED_FIRE_MODES = [
  FireMode.SEMI,
  FireMode.FULL_AUTO,
  FireMode.RAMPING_NXL_2026,
  FireMode.CUSTOM_RAMPING
];

// The max BPS of an uncapped profile: the timing rate, or the league cap in the NXL 2026 mode.
function uncappedMaxBps(fireMode, timingRate) {
  return fireMode === FireMode.RAMPING_NXL_2026 ? '10.5' : timingRate;
}

describe('maxBpsForTiming for each eye mode', () => {
  test('eye off: the dwell open is the open phase in every fire mode', () => {
    for (const fireMode of Object.values(FireMode)) {
      const profile = timing({ fireMode });
      expect(cockingOpenTenthsMs(profile)).toBe(50);
      // 60 + 50 + 300 = 410 tenths of a ms; 10000 / 410 = 24.4 BPS.
      expect(maxBpsForTiming(profile), fireMode).toBe(uncappedMaxBps(fireMode, '24.4'));
    }
  });

  test('eye on, eye-gated fire mode: the eye debounce replaces the dwell open', () => {
    for (const eyeMode of SENSOR_EYE_MODES) {
      for (const fireMode of EYE_GATED_FIRE_MODES) {
        const profile = timing({ eyeMode, fireMode });
        expect(cockingOpenTenthsMs(profile)).toBe(20);
        // 60 + 20 + 300 = 380 tenths of a ms; 10000 / 380 = 26.3 BPS.
        expect(maxBpsForTiming(profile), fireMode).toBe(uncappedMaxBps(fireMode, '26.3'));
      }
    }
  });

  test('eye on, mechanical or trigger only: the fire mode does not read the eye', () => {
    for (const eyeMode of SENSOR_EYE_MODES) {
      for (const fireMode of [FireMode.MECHANICAL, FireMode.TRIGGER_ONLY]) {
        expect(cockingOpenTenthsMs(timing({ eyeMode, fireMode }))).toBe(50);
      }
    }
  });

  test('eye on: a zero eye debounce uses the firmware default of 2.0 ms', () => {
    expect(cockingOpenTenthsMs(timing({ eyeMode: EyeMode.REFLECTIVE, eyeDebounce: 0 }))).toBe(20);
  });

  test('eye on: a zero timeout uses the firmware default of 500 ms', () => {
    const profile = timing({ eyeMode: EyeMode.BREAK_BEAM, eyeDebounce: 6000, cockingSolenoidTimeout: 0 });
    expect(cockingOpenTenthsMs(profile)).toBe(5000);
  });

  test('eye on: a timeout shorter than the eye debounce ends the open phase', () => {
    const profile = timing({ eyeMode: EyeMode.BREAK_BEAM, eyeDebounce: 100, cockingSolenoidTimeout: 30 });
    expect(cockingOpenTenthsMs(profile)).toBe(30);
  });

  test('eye on: the fire-rate cap still limits the rate', () => {
    expect(maxBpsForTiming(timing({ eyeMode: EyeMode.REFLECTIVE, fireRateCapTenths: 105 }))).toBe('10.5');
  });
});

// The league sets the cap of the NXL 2026 mode, and the board does not read the cap of the
// profile. Mirrors the NXL tests of MarkerProfileTest in core:model.
describe('maxBpsForTiming in the NXL 2026 mode', () => {
  test('each stored cap gives the league cap', () => {
    // Timing rate 24.4 BPS. Uncapped, below the limit, at the limit and above the limit.
    for (const fireRateCapTenths of [0, 50, NXL_2026_RATE_CAP, 150, RATE_CAP_MAX]) {
      const league = timing({ fireMode: FireMode.RAMPING_NXL_2026, fireRateCapTenths });
      expect(effectiveFireRateCapTenths(league), fireRateCapTenths).toBe(NXL_2026_RATE_CAP);
      expect(maxBpsForTiming(league), fireRateCapTenths).toBe('10.5');
    }
  });

  test('a timing rate below the league cap still shows', () => {
    // 60 + 50 + 1890 = 2000 tenths of a ms; 10000 / 2000 = 5.0 BPS.
    const slow = timing({ fireMode: FireMode.RAMPING_NXL_2026, cockingSolenoidDwellClosed: 1890 });
    expect(maxBpsForTiming(slow)).toBe('5.0');
  });

  test('each other mode uses the cap of the profile', () => {
    for (const fireMode of Object.values(FireMode)) {
      if (fireMode === FireMode.RAMPING_NXL_2026) continue;
      const capped = timing({ fireMode, fireRateCapTenths: 150 });
      expect(effectiveFireRateCapTenths(capped), fireMode).toBe(150);
      expect(maxBpsForTiming(capped), fireMode).toBe('15.0');
    }
  });
});

describe('maxBpsForTiming in Open Bolt mode', () => {
  test('when CON + COFF dominates over SDEL + SON, the cocking sequence sets the cycle time', () => {
    // CON (50) + COFF (300) = 350 > SDEL (60) + SON (45) = 105; 10000 / 350 = 28.6 BPS.
    for (const fireMode of EYE_GATED_FIRE_MODES) {
      const profile = timing({
        fireMode,
        cockingSolenoidDelay: 900, // ignored in Open Bolt mode
        searSolenoidDelay: 60,
        searSolenoidDwell: 45
      });
      expect(usesBoltMode(fireMode)).toBe(true);
      expect(usesOpenBolt(profile, MarkerBoltMode.OPEN)).toBe(true);
      expect(maxBpsForTiming(profile, MarkerBoltMode.OPEN), fireMode).toBe(uncappedMaxBps(fireMode, '28.6'));
    }
  });

  test('when SDEL + SON dominates over CON + COFF, the sear sequence sets the cycle time', () => {
    // SDEL (360) + SON (45) = 405 > CON (50) + COFF (300) = 350; 10000 / 405 = 24.7 BPS.
    const profile = timing({
      fireMode: FireMode.SEMI,
      cockingSolenoidDelay: 60,
      searSolenoidDelay: 360,
      searSolenoidDwell: 45
    });
    expect(maxBpsForTiming(profile, MarkerBoltMode.OPEN)).toBe('24.7');
  });

  test('eye on in Open Bolt mode replaces CON with eye debounce in max(CON + COFF, SDEL + SON)', () => {
    // eyeDebounce (20) + COFF (300) = 320 > SDEL (60) + SON (45) = 105; 10000 / 320 = 31.3 BPS.
    const profile = timing({
      fireMode: FireMode.SEMI,
      eyeMode: EyeMode.REFLECTIVE,
      searSolenoidDelay: 60,
      searSolenoidDwell: 45
    });
    expect(maxBpsForTiming(profile, MarkerBoltMode.OPEN)).toBe('31.3');
  });

  test('MECHANICAL, TRIGGER_ONLY and UNKNOWN ignore boltMode OPEN and use closed-bolt timing', () => {
    for (const fireMode of [FireMode.MECHANICAL, FireMode.TRIGGER_ONLY, FireMode.UNKNOWN]) {
      const profile = timing({
        fireMode,
        cockingSolenoidDelay: 60,
        searSolenoidDelay: 500,
        searSolenoidDwell: 500
      });
      expect(usesBoltMode(fireMode), fireMode).toBe(false);
      expect(usesOpenBolt(profile, MarkerBoltMode.OPEN), fireMode).toBe(false);
      // Uses closed-bolt cycle: 60 + 50 + 300 = 410 -> 24.4 BPS.
      expect(maxBpsForTiming(profile, MarkerBoltMode.OPEN), fireMode).toBe('24.4');
    }
  });
});
