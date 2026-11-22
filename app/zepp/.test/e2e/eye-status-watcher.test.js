// Unit tests for EyeStatusWatcher.

import '../mocks/runtime';
import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { installRadio } from '../mocks/zos-ble';
import { EyeStatusUuids } from '../../src/bluetooth/eyeStatus';
import {
  EYE_STALE_MS,
  EYE_UNWATCH_DELAY_MS,
  EyeReadingKind,
  EyeStatusWatcher
} from '../../src/bluetooth/EyeStatusWatcher';
import { EyeSensorState } from '../../src/protocol/OtsModelMapper';

function createFakeCcc() {
  const calls = [];
  const pending = [];
  let inFlight = 0;
  let maxInFlight = 0;

  const setNotifications = (enable) => {
    calls.push(enable);
    inFlight++;
    if (inFlight > maxInFlight) maxInFlight = inFlight;
    return new Promise((resolve, reject) => {
      pending.push({
        enable,
        resolve: () => {
          inFlight--;
          resolve();
        },
        reject: (err) => {
          inFlight--;
          reject(err);
        }
      });
    });
  };

  return {
    calls,
    pending,
    setNotifications,
    get maxInFlight() {
      return maxInFlight;
    },
    resolveNext() {
      const next = pending.shift();
      if (!next) throw new Error('No pending CCC write');
      next.resolve();
    },
    rejectNext(err) {
      const next = pending.shift();
      if (!next) throw new Error('No pending CCC write');
      next.reject(err);
    }
  };
}

function timeoutError(message) {
  const err = new Error(message);
  err.isTimeout = true;
  return err;
}

function bytesFromHex(hex) {
  const bytes = new Uint8Array(hex.length / 2);
  for (let i = 0; i < bytes.length; i++) {
    bytes[i] = parseInt(hex.slice(i * 2, i * 2 + 2), 16);
  }
  return bytes;
}

describe('EyeStatusWatcher', () => {
  let watchers;

  beforeEach(() => {
    watchers = [];
    vi.useFakeTimers();
  });

  afterEach(() => {
    watchers.forEach(w => w.close());
    watchers = [];
    installRadio(null);
    vi.useRealTimers();
    vi.restoreAllMocks();
  });

  function makeWatcher(options) {
    const w = new EyeStatusWatcher(options);
    watchers.push(w);
    return w;
  }

  test('the first watch gives Waiting at once and enables notifications once', () => {
    const ccc = createFakeCcc();
    const w = makeWatcher({ supported: true, setNotifications: ccc.setNotifications });

    const seen1 = [];
    const seen2 = [];
    w.watch(s => seen1.push(s));
    w.watch(s => seen2.push(s));

    expect(seen1).toEqual([{ kind: EyeReadingKind.Waiting }]);
    expect(seen2).toEqual([{ kind: EyeReadingKind.Waiting }]);
    expect(w.state).toEqual({ kind: EyeReadingKind.Waiting });
    expect(ccc.calls).toEqual([true]);
  });

  test('a sample gives Reading to each listener and returns to Waiting after 1 s', async () => {
    const ccc = createFakeCcc();
    const w = makeWatcher({ supported: true, setNotifications: ccc.setNotifications });

    const seen1 = [];
    const seen2 = [];
    w.watch(s => seen1.push(s));
    w.watch(s => seen2.push(s));
    ccc.resolveNext();
    await Promise.resolve();

    const sample = { levelRaw: 2048, fullScaleRaw: 4095, state: EyeSensorState.ACTIVE };
    w.acceptReading(sample);

    expect(seen1[seen1.length - 1]).toEqual({ kind: EyeReadingKind.Reading, reading: sample });
    expect(seen2[seen2.length - 1]).toEqual({ kind: EyeReadingKind.Reading, reading: sample });

    await vi.advanceTimersByTimeAsync(EYE_STALE_MS - 1);
    expect(w.state).toEqual({ kind: EyeReadingKind.Reading, reading: sample });

    await vi.advanceTimersByTimeAsync(1);
    expect(w.state).toEqual({ kind: EyeReadingKind.Waiting });
    expect(seen1[seen1.length - 1]).toEqual({ kind: EyeReadingKind.Waiting });
    expect(seen2[seen2.length - 1]).toEqual({ kind: EyeReadingKind.Waiting });
  });

  test('the last unwatch disables notifications after 2 s unless a watch cancels it', async () => {
    const ccc = createFakeCcc();
    const w = makeWatcher({ supported: true, setNotifications: ccc.setNotifications });

    const unwatch1 = w.watch(() => {});
    ccc.resolveNext();
    await Promise.resolve();

    const sample = { levelRaw: 1200, fullScaleRaw: 4095, state: EyeSensorState.ACTIVE };
    w.acceptReading(sample);

    unwatch1();
    // A second unwatch call does nothing.
    unwatch1();

    await vi.advanceTimersByTimeAsync(500);
    expect(ccc.calls).toEqual([true]);

    const seen2 = [];
    const unwatch2 = w.watch(s => seen2.push(s));
    expect(seen2).toEqual([{ kind: EyeReadingKind.Reading, reading: sample }]);
    expect(w.state).toEqual({ kind: EyeReadingKind.Reading, reading: sample });

    await vi.advanceTimersByTimeAsync(EYE_UNWATCH_DELAY_MS);
    expect(ccc.calls).toEqual([true]);

    unwatch2();
    await vi.advanceTimersByTimeAsync(EYE_UNWATCH_DELAY_MS - 1);
    expect(ccc.calls).toEqual([true]);

    await vi.advanceTimersByTimeAsync(1);
    expect(ccc.calls).toEqual([true, false]);
    expect(w.state).toBeNull();
  });

  test('serializes on, off, and on writes with never two in flight', async () => {
    const ccc = createFakeCcc();
    const w = makeWatcher({ supported: true, setNotifications: ccc.setNotifications });

    const unwatch1 = w.watch(() => {});
    ccc.resolveNext();
    await Promise.resolve();
    expect(ccc.calls).toEqual([true]);

    unwatch1();
    await vi.advanceTimersByTimeAsync(EYE_UNWATCH_DELAY_MS);
    expect(ccc.calls).toEqual([true, false]);
    expect(ccc.pending).toHaveLength(1);

    // Watch again while the off write is still in flight.
    const seen = [];
    w.watch(s => seen.push(s));
    expect(seen).toEqual([{ kind: EyeReadingKind.Waiting }]);
    expect(ccc.calls).toEqual([true, false]);

    ccc.resolveNext();
    await Promise.resolve();
    expect(ccc.calls).toEqual([true, false, true]);
    expect(ccc.maxInFlight).toBe(1);

    ccc.resolveNext();
    await Promise.resolve();
    expect(ccc.pending).toHaveLength(0);
    expect(ccc.maxInFlight).toBe(1);
  });

  test('an enable failure gives Unsupported for a plain error and Failed for a timeout', async () => {
    const cccPlain = createFakeCcc();
    const wPlain = makeWatcher({ supported: true, setNotifications: cccPlain.setNotifications });

    const seenPlain = [];
    const unwatchPlain = wPlain.watch(s => seenPlain.push(s));
    cccPlain.rejectNext(new Error('GATT refused'));
    await Promise.resolve();
    await Promise.resolve();

    expect(wPlain.state).toEqual({ kind: EyeReadingKind.Unsupported });
    expect(seenPlain).toEqual([
      { kind: EyeReadingKind.Waiting },
      { kind: EyeReadingKind.Unsupported }
    ]);

    // No retry until all watchers leave and 2 s pass.
    unwatchPlain();
    await vi.advanceTimersByTimeAsync(1000);
    const unwatchEarly = wPlain.watch(() => {});
    expect(cccPlain.calls).toEqual([true]);
    expect(wPlain.state).toEqual({ kind: EyeReadingKind.Unsupported });

    unwatchEarly();
    await vi.advanceTimersByTimeAsync(EYE_UNWATCH_DELAY_MS);
    wPlain.watch(() => {});
    expect(cccPlain.calls).toEqual([true, true]);
    expect(wPlain.state).toEqual({ kind: EyeReadingKind.Waiting });

    const cccTimeout = createFakeCcc();
    const wTimeout = makeWatcher({ supported: true, setNotifications: cccTimeout.setNotifications });
    const seenTimeout = [];
    wTimeout.watch(s => seenTimeout.push(s));
    cccTimeout.rejectNext(timeoutError('Subscribe timed out'));
    await Promise.resolve();
    await Promise.resolve();

    expect(wTimeout.state).toEqual({ kind: EyeReadingKind.Failed });
    expect(seenTimeout).toEqual([
      { kind: EyeReadingKind.Waiting },
      { kind: EyeReadingKind.Failed }
    ]);
  });

  test('an unsupported board gives Unsupported and never writes the CCC', async () => {
    const ccc = createFakeCcc();
    const w = makeWatcher({ supported: false, setNotifications: ccc.setNotifications });

    const seen = [];
    const unwatch = w.watch(s => seen.push(s));
    expect(seen).toEqual([{ kind: EyeReadingKind.Unsupported }]);
    expect(w.state).toEqual({ kind: EyeReadingKind.Unsupported });
    expect(ccc.calls).toEqual([]);

    unwatch();
    await vi.advanceTimersByTimeAsync(EYE_UNWATCH_DELAY_MS);
    expect(w.state).toEqual({ kind: EyeReadingKind.Unsupported });
    expect(ccc.calls).toEqual([]);
  });

  test('close sends null once, clears timers, writes no CCC, and gives null to later watches', async () => {
    const ccc = createFakeCcc();
    const w = makeWatcher({ supported: true, setNotifications: ccc.setNotifications });

    const seen = [];
    const unwatch = w.watch(s => seen.push(s));
    ccc.resolveNext();
    await Promise.resolve();

    w.acceptReading({ levelRaw: 1000, fullScaleRaw: 4095, state: EyeSensorState.ACTIVE });
    const countBeforeClose = seen.length;

    w.close();
    w.close();
    unwatch();

    expect(w.closed).toBe(true);
    expect(w.state).toBeNull();
    expect(seen.slice(countBeforeClose)).toEqual([null]);
    expect(ccc.calls).toEqual([true]);

    await vi.advanceTimersByTimeAsync(EYE_UNWATCH_DELAY_MS + EYE_STALE_MS);
    expect(seen).toHaveLength(countBeforeClose + 1);
    expect(ccc.calls).toEqual([true]);

    const lateSeen = [];
    const lateUnwatch = w.watch(s => lateSeen.push(s));
    lateUnwatch();
    expect(lateSeen).toEqual([null]);
  });

  test('routes notifications by address, decodes valid bytes, logs one decode error, and removes the route on close', async () => {
    const logSpy = vi.spyOn(console, 'log').mockImplementation(() => {});
    let deliver = null;
    installRadio({
      attach(d) {
        deliver = d;
      },
      detach() {}
    });

    const ccc = createFakeCcc();
    const w = makeWatcher({
      supported: true,
      setNotifications: ccc.setNotifications,
      address: 'AA:BB:CC:DD:EE:01'
    });

    const seen = [];
    w.watch(s => seen.push(s));

    // Accept a reading while the CCC write is still in flight.
    deliver.deliverNotification({
      address: 'AA:BB:CC:DD:EE:01',
      characteristicUuid: EyeStatusUuids.Status,
      value: bytesFromHex('08ff1f10ff1f1801').buffer
    });

    expect(seen[seen.length - 1]).toEqual({
      kind: EyeReadingKind.Reading,
      reading: { levelRaw: 4095, fullScaleRaw: 4095, state: EyeSensorState.ACTIVE }
    });

    // An event from another board address is ignored.
    const countBeforeOther = seen.length;
    deliver.deliverNotification({
      address: 'AA:BB:CC:DD:EE:99',
      characteristicUuid: EyeStatusUuids.Status,
      value: bytesFromHex('080010ff1f1801').buffer
    });
    expect(seen).toHaveLength(countBeforeOther);

    // Invalid bytes do not throw and log only once for this watcher.
    const badEvent = {
      address: 'AA:BB:CC:DD:EE:01',
      characteristicUuid: EyeStatusUuids.Status,
      value: bytesFromHex('ff').buffer
    };
    expect(() => deliver.deliverNotification(badEvent)).not.toThrow();
    expect(() => deliver.deliverNotification(badEvent)).not.toThrow();
    expect(logSpy).toHaveBeenCalledTimes(1);

    // close() removes the router route.
    w.close();
    const countAfterClose = seen.length;
    deliver.deliverNotification({
      address: 'AA:BB:CC:DD:EE:01',
      characteristicUuid: EyeStatusUuids.Status,
      value: bytesFromHex('080010ff1f1801').buffer
    });
    expect(seen).toHaveLength(countAfterClose);
    expect(logSpy).toHaveBeenCalledTimes(1);
  });

  test('ignores a sample when no listener is active', () => {
    const ccc = createFakeCcc();
    const w = makeWatcher({ supported: true, setNotifications: ccc.setNotifications });

    w.acceptReading({ levelRaw: 500, fullScaleRaw: 4095, state: EyeSensorState.ACTIVE });
    expect(w.state).toBeNull();
  });

  test('catches an error from one listener and still calls the next listener', () => {
    const logSpy = vi.spyOn(console, 'log').mockImplementation(() => {});
    const ccc = createFakeCcc();
    const w = makeWatcher({ supported: true, setNotifications: ccc.setNotifications });

    const seen = [];
    w.watch(() => {
      throw new Error('listener boom');
    });
    w.watch(s => seen.push(s));

    const sample = { levelRaw: 300, fullScaleRaw: 4095, state: EyeSensorState.ACTIVE };
    w.acceptReading(sample);

    expect(seen).toEqual([
      { kind: EyeReadingKind.Waiting },
      { kind: EyeReadingKind.Reading, reading: sample }
    ]);
    expect(logSpy).toHaveBeenCalled();
  });
});
