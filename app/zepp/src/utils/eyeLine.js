// Formats the live eye line on the EYE_THRESHOLD stepper.
// The meaning of each text matches the profile_eye_* strings of the phone app.
// Keep both sets of texts in step. The level text can show the same number as
// the threshold while the result is "Ball", because the result uses raw counts.

import { EyeReadingKind } from '../bluetooth/EyeStatusWatcher';
import { EyeSensorState } from '../protocol/OtsModelMapper';
import { eyeLevelPercent, eyeSeesBall } from './eyeBallRule';

export const EYE_TEXT = {
  ball: 'Ball',
  empty: 'Empty',
  eyeOff: 'Select an eye profile on board',
  overrideOff: 'Turn the eyes on at the board',
  fault: 'The board reports an eye fault',
  unsupported: 'Update firmware for eye value'
};

export const EYE_LINE_COLOR = {
  ball: 0x00C853,
  empty: 0xFFFFFF,
  hint: 0x888888,
  fault: 0xFF5252
};

export function eyeLine(eyeState, profile) {
  if (!eyeState) return null;

  if (eyeState.kind === EyeReadingKind.Unsupported) {
    return { text: EYE_TEXT.unsupported, color: EYE_LINE_COLOR.hint };
  }

  if (eyeState.kind !== EyeReadingKind.Reading || !eyeState.reading) {
    return null;
  }

  const reading = eyeState.reading;
  switch (reading.state) {
    case EyeSensorState.EYE_OFF:
      return { text: EYE_TEXT.eyeOff, color: EYE_LINE_COLOR.hint };
    case EyeSensorState.OVERRIDE_OFF:
      return { text: EYE_TEXT.overrideOff, color: EYE_LINE_COLOR.hint };
    case EyeSensorState.FAULT:
      return { text: EYE_TEXT.fault, color: EYE_LINE_COLOR.fault };
    case EyeSensorState.ACTIVE: {
      const pct = eyeLevelPercent(reading);
      if (pct === null) return null;
      const base = `Eye ${pct.toFixed(1)}%`;
      const seesBall = profile
        ? eyeSeesBall(profile.eyeMode, reading.levelRaw, reading.fullScaleRaw, profile.eyeThreshold)
        : null;
      if (seesBall === true) {
        return { text: `${base} - ${EYE_TEXT.ball}`, color: EYE_LINE_COLOR.ball };
      }
      if (seesBall === false) {
        return { text: `${base} - ${EYE_TEXT.empty}`, color: EYE_LINE_COLOR.empty };
      }
      return { text: base, color: EYE_LINE_COLOR.empty };
    }
    default:
      return null;
  }
}
