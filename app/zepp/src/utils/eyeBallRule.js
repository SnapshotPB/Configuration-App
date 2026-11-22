// This file is a hand-written mirror of EyeBallRule.kt and
// EyeReading.levelPercent in core:model. The fixture eye-ball-rule.json keeps
// both copies the same. Keep both copies in step.

import {
  EYE_THRESHOLD_DEFAULT,
  EYE_THRESHOLD_MAX,
  EyeMode,
  EyeSensorState
} from '../protocol/OtsModelMapper';

export const EYE_ADC_FULL_SCALE_RAW = 4095;

function effectiveEyeThresholdPercent(thresholdPercent) {
  const p = Math.floor(Number(thresholdPercent));
  if (!(p > 0)) return EYE_THRESHOLD_DEFAULT;
  if (p > EYE_THRESHOLD_MAX) return EYE_THRESHOLD_MAX;
  return p;
}

// Math.floor copies the integer division of the firmware. The stepper must use
// this raw value so the shown result agrees with the board at each count.
export function eyeThresholdRaw(fullScaleRaw, thresholdPercent) {
  const full = Number(fullScaleRaw);
  if (!(full > 0)) return 0;
  return Math.floor((effectiveEyeThresholdPercent(thresholdPercent) * full) / 100);
}

export function eyeSeesBall(eyeMode, levelRaw, fullScaleRaw, thresholdPercent) {
  if (levelRaw === null || levelRaw === undefined) return null;
  if (eyeMode === EyeMode.BREAK_BEAM) {
    return levelRaw <= eyeThresholdRaw(fullScaleRaw, thresholdPercent);
  }
  if (eyeMode === EyeMode.REFLECTIVE) {
    return levelRaw > eyeThresholdRaw(fullScaleRaw, thresholdPercent);
  }
  return null;
}

export function eyeLevelPercent(reading) {
  if (
    reading == null ||
    reading.state !== EyeSensorState.ACTIVE ||
    reading.levelRaw == null ||
    !(reading.fullScaleRaw > 0)
  ) {
    return null;
  }
  return (reading.levelRaw * 100) / reading.fullScaleRaw;
}
