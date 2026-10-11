import { createWidget, widget, align, deleteWidget } from '@zos/ui';
import { px } from './px';

const TOAST_MS = 1500;

// A short message at the top of the screen that clears itself. A page owns one Toast, and the
// toast owns its widget, so the page's dynamic widgets never hold it and only one owner deletes
// it. The page calls clear() before it redraws and in onDestroy.
export class Toast {
  constructor() {
    this._widget = null;
    this._timer = null;
  }

  // Shows [text] in [color]. At most one toast is visible: an earlier one is cleared first.
  show(text, color) {
    this.clear();
    this._widget = createWidget(widget.TEXT, {
      x: px(120),
      y: px(20),
      w: px(240),
      h: px(35),
      color: color,
      text_size: px(18),
      align_h: align.CENTER_H,
      text: text
    });
    this._timer = setTimeout(() => {
      this._timer = null;
      this._deleteWidget();
    }, TOAST_MS);
  }

  // Cancels the expiry timer and deletes the widget, so a redraw or a page exit cannot leave
  // either behind.
  clear() {
    if (this._timer) {
      clearTimeout(this._timer);
      this._timer = null;
    }
    this._deleteWidget();
  }

  _deleteWidget() {
    const w = this._widget;
    this._widget = null;
    if (!w) return;
    try {
      deleteWidget(w);
    } catch (e) {
      console.log("Error deleting toast widget:", e);
    }
  }
}
