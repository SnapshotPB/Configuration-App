// Stand-in for @zos/router. Records every navigation and, when the page host
// installs a handler, actually drives the page stack so a whole flow
// (device_list -> connecting -> device_config) runs end to end.

const navigations = [];
let handler = null;

export function push(options) {
  navigations.push({ type: 'push', url: options && options.url, params: options && options.param });
  if (handler) handler('push', options);
}

export function replace(options) {
  navigations.push({ type: 'replace', url: options && options.url, params: options && options.param });
  if (handler) handler('replace', options);
}

export function back(options) {
  navigations.push({ type: 'back' });
  if (handler) handler('back', options || {});
}

export function home(options) {
  navigations.push({ type: 'home' });
  if (handler) handler('home', options || {});
}

export const routerRecorder = {
  navigations,

  reset() {
    navigations.length = 0;
    handler = null;
  },

  setHandler(fn) {
    handler = fn;
  },

  urls() {
    return navigations.map(n => `${n.type}:${n.url || ''}`);
  },

  last() {
    return navigations[navigations.length - 1] || null;
  }
};
