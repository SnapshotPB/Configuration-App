import {
  BoardLanguage,
  BoardScreenBrightness,
  SCREEN_OFF_DELAY_DEFAULT,
  SCREEN_OFF_DELAY_MAX
} from '../protocol/OtsModelMapper';

// Screen-off delays the watch offers, in seconds. The watch has no keypad, so it offers a short
// list rather than the whole 1-999 range; the phone gives exact control. The Wear OS app offers the
// same list, so the two watches agree.
export const SCREEN_OFF_DELAY_PRESETS = [
  SCREEN_OFF_DELAY_DEFAULT, 10, 15, 30, 60, 120, 300, SCREEN_OFF_DELAY_MAX
];

// The delays to offer for a board that holds [current]. The board can report any delay from 1 to
// 999, and the phone can set one. A value that is not a preset joins the list in order, so the
// watch shows it as the active choice and the user can return to it after looking at the others.
// Without this a single tap would lose a delay the watch could not offer again.
export function screenOffDelayOptions(current) {
  if (current == null || SCREEN_OFF_DELAY_PRESETS.indexOf(current) >= 0) {
    return SCREEN_OFF_DELAY_PRESETS;
  }
  return SCREEN_OFF_DELAY_PRESETS.concat([current]).sort((a, b) => a - b);
}

// The board settings the watch can choose, one entry per setting. The protocol carries only a code
// or a number, so the watch supplies the names it shows. The names match the KMP
// board_language_*, board_brightness_* and board_screen_off_delay_seconds strings.
//
// `field` is the device field the choice writes. `options` are the values to offer, in the order
// they appear. `label` gives the name the watch shows for one value.
export const BOARD_SETTINGS = {
  language: {
    title: 'Board Language',
    field: 'language',
    options: Object.values(BoardLanguage),
    names: {
      [BoardLanguage.EN]: 'English',
      [BoardLanguage.ES]: 'Spanish',
      [BoardLanguage.DE]: 'German',
      [BoardLanguage.FR]: 'French'
    }
  },
  screenBrightness: {
    title: 'Screen Brightness',
    field: 'screenBrightness',
    options: Object.values(BoardScreenBrightness),
    names: {
      [BoardScreenBrightness.LOW]: 'Low',
      [BoardScreenBrightness.DEFAULT]: 'Default',
      [BoardScreenBrightness.HIGH]: 'High'
    }
  },
  screenOffDelay: {
    title: 'Screen Off Delay',
    field: 'screenOffDelay',
    // The list depends on the value the board holds, so the page calls screenOffDelayOptions.
    options: SCREEN_OFF_DELAY_PRESETS,
    optionsFor: screenOffDelayOptions,
    names: null // a delay shows as seconds; see boardSettingLabel
  }
};

// The name the watch shows for one value of a board setting. A delay has no name of its own, so it
// shows as seconds. A value the app does not know shows as itself rather than as nothing.
export function boardSettingLabel(settingKey, value) {
  if (value == null) return '';
  const setting = BOARD_SETTINGS[settingKey];
  if (!setting) return String(value);
  if (!setting.names) return `${value} s`;
  return setting.names[value] || String(value);
}
