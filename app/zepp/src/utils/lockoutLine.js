import { createWidget, widget, align, text_style } from '@zos/ui';
import { SAVE_COLOR_WARN } from './saveOutcome';
import { px } from './px';

// The line a page shows in place of Save while the board reports lockout. device.proto: a board in
// lockout does not accept a change until the user turns off the lockout switch on the board. The
// app reads the lockout only when it connects, so the user must also connect again.
export const SAVE_BLOCKED_LOCKOUT_TEXT = 'Board in lockout.\nTurn off lockout, then reconnect.';

// Draws the lockout line at [y], adds it to [widgets] and returns the next y. The page owns the
// widget and deletes it with its other dynamic widgets.
export function drawLockoutLine(widgets, y) {
  const line = createWidget(widget.TEXT, {
    x: px(60),
    y: y,
    w: px(360),
    h: px(50),
    color: SAVE_COLOR_WARN,
    text_size: px(18),
    align_h: align.CENTER_H,
    align_v: align.CENTER_V,
    // WRAP breaks a line that is too long for the width, so no text is cut off.
    text_style: text_style.WRAP,
    text: SAVE_BLOCKED_LOCKOUT_TEXT
  });
  widgets.push(line);
  return y + px(65);
}
