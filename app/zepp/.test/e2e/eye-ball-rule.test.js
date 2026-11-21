// Parity tests for the eye ball rule against the shared core:model fixture.

import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, test } from 'vitest';
import { EyeMode, EyeSensorState } from '../../src/protocol/OtsModelMapper';
import {
  EYE_ADC_FULL_SCALE_RAW,
  eyeLevelPercent,
  eyeSeesBall,
  eyeThresholdRaw
} from '../../src/utils/eyeBallRule';

const fixturePath = fileURLToPath(
  new URL('../../../../core/model/src/jvmTest/resources/eye-ball-rule.json', import.meta.url)
);
const FIXTURE = JSON.parse(readFileSync(fixturePath, 'utf8'));

describe('eye ball rule', () => {
  test('the fixture has 48 cases and covers all three eye modes', () => {
    expect(FIXTURE.cases).toHaveLength(48);
    const modes = new Set(FIXTURE.cases.map(c => c.eyeMode));
    expect(modes.has(EyeMode.OFF)).toBe(true);
    expect(modes.has(EyeMode.REFLECTIVE)).toBe(true);
    expect(modes.has(EyeMode.BREAK_BEAM)).toBe(true);
  });

  test('matches the fixture for each case', () => {
    FIXTURE.cases.forEach((c) => {
      const label =
        `mode=${c.eyeMode} fullScale=${c.fullScaleRaw} threshold=${c.thresholdPercent} level=${c.levelRaw}`;
      expect(eyeThresholdRaw(c.fullScaleRaw, c.thresholdPercent), label).toBe(c.thresholdRaw);
      expect(
        eyeSeesBall(c.eyeMode, c.levelRaw, c.fullScaleRaw, c.thresholdPercent),
        label
      ).toBe(c.seesBall);
    });
  });

  test('returns null for UNKNOWN mode and for a null level', () => {
    expect(eyeSeesBall(EyeMode.UNKNOWN, 2047, EYE_ADC_FULL_SCALE_RAW, 50)).toBeNull();
    expect(eyeSeesBall(EyeMode.BREAK_BEAM, null, EYE_ADC_FULL_SCALE_RAW, 50)).toBeNull();
    expect(eyeSeesBall(EyeMode.REFLECTIVE, undefined, EYE_ADC_FULL_SCALE_RAW, 50)).toBeNull();
  });

  test('returns 0 for a full scale of 0 or less', () => {
    expect(eyeThresholdRaw(0, 50)).toBe(0);
    expect(eyeThresholdRaw(-1, 50)).toBe(0);
  });

  test('computes the level percent only for an ACTIVE sample with a positive full scale', () => {
    expect(
      eyeLevelPercent({
        levelRaw: 2047,
        fullScaleRaw: EYE_ADC_FULL_SCALE_RAW,
        state: EyeSensorState.ACTIVE
      })
    ).toBeCloseTo(49.99, 2);

    expect(
      eyeLevelPercent({
        levelRaw: null,
        fullScaleRaw: EYE_ADC_FULL_SCALE_RAW,
        state: EyeSensorState.ACTIVE
      })
    ).toBeNull();

    expect(
      eyeLevelPercent({
        levelRaw: 2047,
        fullScaleRaw: EYE_ADC_FULL_SCALE_RAW,
        state: EyeSensorState.EYE_OFF
      })
    ).toBeNull();

    expect(
      eyeLevelPercent({
        levelRaw: 2047,
        fullScaleRaw: 0,
        state: EyeSensorState.ACTIVE
      })
    ).toBeNull();

    expect(eyeLevelPercent(null)).toBeNull();
  });
});
