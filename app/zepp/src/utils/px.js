import { px as zosPx } from '@zos/utils';
import { getDeviceInfo } from '@zos/device';

export function px(value) {
  if (typeof zosPx === "function") {
    return Math.round(zosPx(value));
  }
  return value; // Fallback
}

// Actual device screen dimensions. Pages must size full-screen backgrounds and
// lay out from these rather than assuming a 480px canvas — app.json targets
// designWidths of 466 (gtr-4) and 480 (others) across round and square devices.
function deviceInfo() {
  try {
    if (typeof getDeviceInfo === "function") {
      return getDeviceInfo();
    }
  } catch (_) {
    // fall through to default
  }
  return null;
}

export function screenWidth() {
  const info = deviceInfo();
  return (info && info.width) || 480; // Fallback
}

export function screenHeight() {
  const info = deviceInfo();
  return (info && info.height) || 480; // Fallback
}
