// Stand-in for @zos/ui. Records the widget tree the pages build so tests can
// assert on rendered text and invoke the recorded click_func handlers to
// simulate taps. Widget handles are opaque to the app (it only ever passes them
// back to deleteWidget), so a plain object is a faithful enough stand-in.

let widgets = [];
let nextId = 1;
let currentPage = 'root';

// Only the widget types this app creates. Values are the type names rather than
// the firmware's numeric ids — the app never inspects them.
export const widget = {
  TEXT: 'TEXT',
  BUTTON: 'BUTTON',
  FILL_RECT: 'FILL_RECT',
  IMG: 'IMG',
  ARC: 'ARC',
  CIRCLE: 'CIRCLE',
  STROKE_RECT: 'STROKE_RECT'
};

export const align = {
  LEFT: 'LEFT',
  CENTER_H: 'CENTER_H',
  RIGHT: 'RIGHT',
  TOP: 'TOP',
  CENTER_V: 'CENTER_V',
  BOTTOM: 'BOTTOM'
};

// Only the text styles this app sets.
export const text_style = {
  NONE: 'NONE',
  WRAP: 'WRAP',
  ELLIPSIS: 'ELLIPSIS'
};

export function createWidget(type, props) {
  const w = {
    id: nextId++,
    type,
    page: currentPage,
    deleted: false,
    props: { ...props },
    getProperty(key) {
      return this.props[key];
    },
    setProperty(key, value) {
      this.props[key] = value;
    },
    addEventListener() {},
    removeEventListener() {}
  };
  widgets.push(w);
  return w;
}

export function deleteWidget(w) {
  if (!w) return;
  w.deleted = true;
  widgets = widgets.filter(existing => existing !== w);
}

// --- Test-facing recorder ---------------------------------------------------

export const uiRecorder = {
  reset() {
    widgets = [];
    nextId = 1;
    currentPage = 'root';
  },

  /** Widgets created from here on are attributed to this page id. */
  setCurrentPage(pageId) {
    currentPage = pageId;
  },

  currentPage() {
    return currentPage;
  },

  /** Zepp frees a page's widgets when it is destroyed; the host calls this. */
  clearPage(pageId) {
    widgets = widgets.filter(w => w.page !== pageId);
  },

  live() {
    return widgets.slice();
  },

  /** Every rendered text string currently on screen, in creation order. */
  texts() {
    return widgets.filter(w => typeof w.props.text === 'string').map(w => w.props.text);
  },

  find(predicate) {
    return widgets.find(predicate) || null;
  },

  findAll(predicate) {
    return widgets.filter(predicate);
  },

  findByText(substring) {
    return (
      widgets.find(w => typeof w.props.text === 'string' && w.props.text.includes(substring)) || null
    );
  },

  hasText(substring) {
    return this.findByText(substring) !== null;
  }
};
