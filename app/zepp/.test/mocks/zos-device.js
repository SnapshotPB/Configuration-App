// Stand-in for @zos/device. Reports a square 480x480 device, matching the
// t-rex-3 / bip-5-unity designWidth the app targets, so px() is 1:1 and
// assertions can reason about the layout the pages compute.

let info = {
  width: 480,
  height: 480,
  screenShape: 1, // 1 = square in the Zepp device profile
  deviceName: 'Virtual Snapshot PB Watch',
  deviceSource: 8716544, // t-rex-3
  keyNumber: 2
};

export function getDeviceInfo() {
  return { ...info };
}

export const deviceRecorder = {
  reset() {
    info = { ...info, width: 480, height: 480 };
  },

  setScreen(width, height) {
    info = { ...info, width, height };
  }
};
