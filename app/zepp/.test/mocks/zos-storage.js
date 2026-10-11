// Stand-in for @zos/storage. An in-memory map behind the same LocalStorage
// class shape DeviceStore constructs. Values are coerced to strings, matching
// the firmware's key/value store (DeviceStore always stores JSON text).

const store = new Map();

export class LocalStorage {
  getItem(key) {
    return store.has(key) ? store.get(key) : undefined;
  }

  setItem(key, value) {
    store.set(key, String(value));
  }

  removeItem(key) {
    store.delete(key);
  }

  clear() {
    store.clear();
  }
}

export const storageRecorder = {
  reset() {
    store.clear();
  },

  raw() {
    return store;
  },

  keys() {
    return Array.from(store.keys());
  }
};
