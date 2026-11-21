// Unit tests for eyeLine.

import { describe, expect, test } from 'vitest';
import { EyeReadingKind } from '../../src/bluetooth/EyeStatusWatcher';
import { EyeMode, EyeSensorState } from '../../src/protocol/OtsModelMapper';
import { EYE_LINE_COLOR, EYE_TEXT, eyeLine } from '../../src/utils/eyeLine';

const REFLECTIVE_60 = { eyeMode: EyeMode.REFLECTIVE, eyeThreshold: 60 };
const BREAK_BEAM_60 = { eyeMode: EyeMode.BREAK_BEAM, eyeThreshold: 60 };

describe('eyeLine', () => {
  test('null, Waiting, and Failed give null', () => {
    expect(eyeLine(null, REFLECTIVE_60)).toBeNull();
    expect(eyeLine({ kind: EyeReadingKind.Waiting }, REFLECTIVE_60)).toBeNull();
    expect(eyeLine({ kind: EyeReadingKind.Failed }, REFLECTIVE_60)).toBeNull();
  });

  test('Unsupported gives the firmware update hint in hint color', () => {
    expect(eyeLine({ kind: EyeReadingKind.Unsupported }, REFLECTIVE_60)).toEqual({
      text: EYE_TEXT.unsupported,
      color: EYE_LINE_COLOR.hint
    });
  });

  test('EYE_OFF, OVERRIDE_OFF, FAULT, and UNKNOWN map to their hint text and color', () => {
    expect(
      eyeLine(
        {
          kind: EyeReadingKind.Reading,
          reading: { levelRaw: null, fullScaleRaw: 4095, state: EyeSensorState.EYE_OFF }
        },
        REFLECTIVE_60
      )
    ).toEqual({ text: EYE_TEXT.eyeOff, color: EYE_LINE_COLOR.hint });

    expect(
      eyeLine(
        {
          kind: EyeReadingKind.Reading,
          reading: { levelRaw: null, fullScaleRaw: 4095, state: EyeSensorState.OVERRIDE_OFF }
        },
        REFLECTIVE_60
      )
    ).toEqual({ text: EYE_TEXT.overrideOff, color: EYE_LINE_COLOR.hint });

    expect(
      eyeLine(
        {
          kind: EyeReadingKind.Reading,
          reading: { levelRaw: null, fullScaleRaw: 4095, state: EyeSensorState.FAULT }
        },
        REFLECTIVE_60
      )
    ).toEqual({ text: EYE_TEXT.fault, color: EYE_LINE_COLOR.fault });

    expect(
      eyeLine(
        {
          kind: EyeReadingKind.Reading,
          reading: { levelRaw: 2047, fullScaleRaw: 4095, state: EyeSensorState.UNKNOWN }
        },
        REFLECTIVE_60
      )
    ).toBeNull();
  });

  test('ACTIVE formats percent with one decimal and applies eyeSeesBall', () => {
    const activeSample = {
      kind: EyeReadingKind.Reading,
      reading: { levelRaw: 2047, fullScaleRaw: 4095, state: EyeSensorState.ACTIVE }
    };

    expect(eyeLine(activeSample, REFLECTIVE_60)).toEqual({
      text: 'Eye 50.0% - Empty',
      color: EYE_LINE_COLOR.empty
    });

    expect(eyeLine(activeSample, BREAK_BEAM_60)).toEqual({
      text: 'Eye 50.0% - Ball',
      color: EYE_LINE_COLOR.ball
    });

    expect(eyeLine(activeSample, { eyeMode: EyeMode.OFF, eyeThreshold: 60 })).toEqual({
      text: 'Eye 50.0%',
      color: EYE_LINE_COLOR.empty
    });

    expect(eyeLine(activeSample, { eyeMode: EyeMode.UNKNOWN, eyeThreshold: 60 })).toEqual({
      text: 'Eye 50.0%',
      color: EYE_LINE_COLOR.empty
    });
  });

  test('ACTIVE with no level or zero full scale gives null', () => {
    expect(
      eyeLine(
        {
          kind: EyeReadingKind.Reading,
          reading: { levelRaw: null, fullScaleRaw: 4095, state: EyeSensorState.ACTIVE }
        },
        REFLECTIVE_60
      )
    ).toBeNull();

    expect(
      eyeLine(
        {
          kind: EyeReadingKind.Reading,
          reading: { levelRaw: 2047, fullScaleRaw: 0, state: EyeSensorState.ACTIVE }
        },
        REFLECTIVE_60
      )
    ).toBeNull();
  });
});
