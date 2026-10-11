import { boardSettingLabel } from './boardSettings';
import { PROFILE_FIELDS, formatProfileFieldValue } from './profileFields';

export const SAVE_COLOR_OK = 0x00C853;
export const SAVE_COLOR_WARN = 0xFFAB00;
export const SAVE_COLOR_FAIL = 0xFF0000;

const BOARD_SETTING_KEYS = ['language', 'screenBrightness', 'screenOffDelay'];

// The message a page shows after the board accepted a write, as { text, color }.
//
// [requested] is the device the page sent, [stored] the store copy after applyWriteResult, and
// [written] the board settings the board reported back (null when the read-back failed).
//
// The board can refuse a language or a brightness it does not know, and it clamps the delay and
// the profile fields. The first value the board kept is named, so the user learns why the screen
// shows a different value than the one chosen. The protocol says a client must not assume that a
// write took effect, so a save with no read-back, or with no value for a chosen setting, is
// reported as not verified rather than as a success.
export function saveOutcome(requested, stored, written) {
  const chosen = BOARD_SETTING_KEYS.filter(key => requested[key] != null);
  const refused = chosen.find(key => (stored[key] || null) !== requested[key]);
  if (refused) {
    const kept = boardSettingLabel(refused, stored[refused]);
    return { text: kept ? `Board kept ${kept}` : 'Setting not applied', color: SAVE_COLOR_WARN };
  }

  const keptField = keptProfileField(requested, stored);
  if (keptField) {
    return { text: `Board kept ${keptField}`, color: SAVE_COLOR_WARN };
  }

  const unverified = !written || chosen.some(key => written[key] == null);
  if (unverified) {
    return { text: 'Saved - not verified', color: SAVE_COLOR_WARN };
  }

  return { text: 'Save Successful!', color: SAVE_COLOR_OK };
}

// The label and the value of the first profile field the board holds at a different value than
// the one sent, or null. The baseline holds the clamped copy of the written profiles, so it is
// compared, not the editable copy: an edit that landed during the write must not read as a
// refusal.
function keptProfileField(requested, stored) {
  const baseline = stored.boardState ? (stored.boardState.knownProfiles || []) : [];
  const sent = requested.knownProfiles || [];
  for (let i = 0; i < sent.length; i++) {
    const held = baseline.find(p => p.id === sent[i].id);
    if (!held) continue;
    for (let j = 0; j < PROFILE_FIELDS.length; j++) {
      const def = PROFILE_FIELDS[j];
      const wanted = sent[i][def.field] == null ? null : Number(sent[i][def.field]);
      const kept = held[def.field] == null ? null : Number(held[def.field]);
      if (wanted !== kept) {
        return `${def.label} ${formatProfileFieldValue(def, kept)}`;
      }
    }
  }
  return null;
}
