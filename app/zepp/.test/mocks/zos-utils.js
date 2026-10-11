// Stand-in for @zos/utils. The real px() scales a design-space value by
// (device width / designWidth). The harness reports a 480px screen by default
// (see zos-device), which matches this app's 480 designWidth, so px() is the
// identity — but it tracks deviceRecorder.setScreen() so a resized device stays
// internally consistent instead of reporting a new width with unscaled layout.
// src/utils/px.js rounds the result either way.

import { getDeviceInfo } from './zos-device';

const DESIGN_WIDTH = 480;

export function px(value) {
  return (value * getDeviceInfo().width) / DESIGN_WIDTH;
}

export function log() {
  return { log() {}, warn() {}, error() {}, debug() {} };
}
