// Simulated eye level source. Only the mock board uses this module.

import { EyeSensorState } from '../protocol/OtsModelMapper';
import { EYE_ADC_FULL_SCALE_RAW } from '../utils/eyeBallRule';

const MIN_LEVEL = 400;
const MAX_LEVEL = 3700;
const STEP = 60;
const HALF_PERIOD = (MAX_LEVEL - MIN_LEVEL) / STEP;
const PERIOD = HALF_PERIOD * 2;
const TICK_MS = 100;

export function simulatedEyeLevel(tick) {
  const t = Math.max(0, Math.floor(Number(tick) || 0)) % PERIOD;
  const steps = t <= HALF_PERIOD ? t : PERIOD - t;
  return MIN_LEVEL + steps * STEP;
}

// Creates a simulated eye source for one mock EyeStatusWatcher.
// The source chains 100 ms setTimeout calls and stops on stop() or watcher.close().
export function createMockEyeSource(getWatcher) {
  let running = false;
  let timer = null;
  let tick = 0;

  function stop() {
    running = false;
    if (timer !== null) {
      clearTimeout(timer);
      timer = null;
    }
  }

  function scheduleNext() {
    timer = setTimeout(() => {
      timer = null;
      const watcher = getWatcher();
      if (!running || !watcher || watcher.closed) {
        stop();
        return;
      }
      watcher.acceptReading({
        levelRaw: simulatedEyeLevel(tick),
        fullScaleRaw: EYE_ADC_FULL_SCALE_RAW,
        state: EyeSensorState.ACTIVE
      });
      tick++;
      if (running && !watcher.closed) {
        scheduleNext();
      }
    }, TICK_MS);
  }

  function start() {
    if (running) return;
    const watcher = getWatcher();
    if (!watcher || watcher.closed) return;
    running = true;
    scheduleNext();
  }

  return { start, stop };
}
