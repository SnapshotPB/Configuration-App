// A fire mode or an eye mode that the board reports and the app does not know. The editor shows
// it as unknown, does not offer it as a choice, and applies the same field rules as
// MarkerProfileField in core:model.

import { describe, expect, test } from 'vitest';
import { PROFILE_CHOICES, profileChoiceLabel, visibleProfileFields } from '../../src/utils/profileFields.js';

describe('unknown modes', () => {
  test('the mode lists do not offer UNKNOWN', () => {
    expect(PROFILE_CHOICES.fireMode.options).not.toContain('UNKNOWN');
    expect(PROFILE_CHOICES.eyeMode.options).not.toContain('UNKNOWN');
  });

  test('an unknown mode shows as Unknown Mode', () => {
    expect(profileChoiceLabel('fireMode', 'UNKNOWN')).toBe('Unknown Mode');
    expect(profileChoiceLabel('eyeMode', 'UNKNOWN')).toBe('Unknown Mode');
  });

  test('an unknown eye mode still shows the eye fields, as in MarkerProfileField', () => {
    const keys = visibleProfileFields({ fireMode: 'SEMI', eyeMode: 'UNKNOWN' }).map(f => f.key);
    expect(keys).toContain('EYE_THRESHOLD');
    expect(keys).toContain('COCKING_SOLENOID_TIMEOUT');
  });
});
