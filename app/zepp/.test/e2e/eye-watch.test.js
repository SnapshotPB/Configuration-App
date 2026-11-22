// End-to-end tests for BleManager.watchEye and the virtual board eye stream.

import { afterEach, describe, expect, test } from 'vitest';
import { startHarness } from '../harness';
import { EYE_UUID, normalizeUuid, OTS_UUID } from '../virtual-board/VirtualBoard';
import { MOCK_DEVICE_ID } from '../../src/bluetooth/BleManager';
import { EyeStatusUuids } from '../../src/bluetooth/eyeStatus';
import { EyeReadingKind } from '../../src/bluetooth/EyeStatusWatcher';
import { simulatedEyeLevel } from '../../src/bluetooth/mockEyeSource';
import { EyeSensorState } from '../../src/protocol/OtsModelMapper';

function eyeSubCalls(radio) {
  return radio
    .callsTo('mstPrepareNotificationChar')
    .filter(c => normalizeUuid(c.uuid) === EYE_UUID.STATUS);
}

describe('BleManager watchEye', () => {
  let h;

  afterEach(() => {
    if (h) h.teardown();
    h = null;
  });

  test('a new board gives Waiting then Reading and subscribes with the eye service UUID', async () => {
    h = await startHarness();
    const { device } = await h.connect();

    const seen = [];
    const unwatch = h.ble.watchEye(device.id, s => seen.push(s));

    expect(seen[0]).toEqual({ kind: EyeReadingKind.Waiting });
    await h.waitFor(() => seen.some(s => s && s.kind === EyeReadingKind.Reading));

    const firstReading = seen.find(s => s && s.kind === EyeReadingKind.Reading);
    expect(firstReading).toEqual({
      kind: EyeReadingKind.Reading,
      reading: {
        levelRaw: 2047,
        fullScaleRaw: 4095,
        state: EyeSensorState.ACTIVE
      }
    });

    const subs = eyeSubCalls(h.radio);
    expect(subs).toHaveLength(1);
    expect(subs[0].enable).toBe(true);
    expect(subs[0].serviceUuid).toBe(EyeStatusUuids.Service);
    unwatch();
  });

  test('a new board eye status reaches the listener on the next notification', async () => {
    h = await startHarness();
    const { device } = await h.connect();

    const seen = [];
    const unwatch = h.ble.watchEye(device.id, s => seen.push(s));
    await h.waitFor(() => seen.some(s => s && s.kind === EyeReadingKind.Reading));

    h.board.setEyeStatus({ levelRaw: 100, fullScaleRaw: 4095, state: 1 });
    await h.waitFor(
      () => seen.some(s => s && s.kind === EyeReadingKind.Reading && s.reading.levelRaw === 100)
    );

    unwatch();
  });

  test('no board notifications for 1 s returns the state to Waiting', async () => {
    h = await startHarness();
    const { device } = await h.connect();

    const seen = [];
    const unwatch = h.ble.watchEye(device.id, s => seen.push(s));
    await h.waitFor(() => seen.some(s => s && s.kind === EyeReadingKind.Reading));

    h.board.eyeSending = false;
    await h.waitFor(() => seen[seen.length - 1] && seen[seen.length - 1].kind === EyeReadingKind.Waiting);
    expect(seen[seen.length - 1]).toEqual({ kind: EyeReadingKind.Waiting });

    unwatch();
  });

  test('unwatch disables eye notifications after 2 s and a watch inside 2 s cancels it', async () => {
    h = await startHarness();
    const { device } = await h.connect();

    const seen = [];
    const unwatch1 = h.ble.watchEye(device.id, s => seen.push(s));
    await h.waitFor(() => seen.some(s => s && s.kind === EyeReadingKind.Reading));
    expect(eyeSubCalls(h.radio).map(c => c.enable)).toEqual([true]);

    // Unwatch, then watch again inside the 2 s delay: no disable write runs.
    unwatch1();
    await new Promise(resolve => setTimeout(resolve, 400));
    const unwatch2 = h.ble.watchEye(device.id, () => {});
    await new Promise(resolve => setTimeout(resolve, 2500));
    expect(eyeSubCalls(h.radio).map(c => c.enable)).toEqual([true]);

    // Now unwatch and let the 2 s delay elapse: one disable write runs and notifications stop.
    unwatch2();
    await h.waitFor(() => eyeSubCalls(h.radio).some(c => c.enable === false));
    expect(eyeSubCalls(h.radio).map(c => c.enable)).toEqual([true, false]);

    const sentAfterOff = h.board.eyeNotificationsSent;
    await new Promise(resolve => setTimeout(resolve, 500));
    expect(h.board.eyeNotificationsSent).toBe(sentAfterOff);
  });

  test('disconnect gives null and sends no eye CCC disable write', async () => {
    h = await startHarness();
    const { device } = await h.connect();

    const seen = [];
    h.ble.watchEye(device.id, s => seen.push(s));
    await h.waitFor(() => seen.some(s => s && s.kind === EyeReadingKind.Reading));

    await new Promise(resolve => h.ble.disconnect(resolve));
    await new Promise(resolve => setTimeout(resolve, 300));

    expect(seen[seen.length - 1]).toBeNull();
    expect(eyeSubCalls(h.radio).filter(c => c.enable === false)).toHaveLength(0);
  });

  test('an old board with missingService fail gives Unsupported and writes no eye CCC', async () => {
    h = await startHarness({
      boardOptions: { eyeService: false },
      faults: { missingService: 'fail' }
    });
    const { device } = await h.connect();

    const seen = [];
    const unwatch = h.ble.watchEye(device.id, s => seen.push(s));

    expect(seen).toEqual([{ kind: EyeReadingKind.Unsupported }]);
    expect(eyeSubCalls(h.radio)).toHaveLength(0);
    unwatch();
  });

  test('an old board with missingService ignore fails the eye subscribe and gives Unsupported', async () => {
    h = await startHarness({
      boardOptions: { eyeService: false },
      faults: { missingService: 'ignore' }
    });
    const { device } = await h.connect();

    const seen = [];
    const unwatch = h.ble.watchEye(device.id, s => seen.push(s));

    await h.waitFor(() => seen[seen.length - 1] && seen[seen.length - 1].kind === EyeReadingKind.Unsupported);
    expect(seen[0]).toEqual({ kind: EyeReadingKind.Waiting });
    expect(seen[seen.length - 1]).toEqual({ kind: EyeReadingKind.Unsupported });
    expect(eyeSubCalls(h.radio)).toHaveLength(1);
    unwatch();
  });

  test('a silent eye subscribe times out and gives Failed', async () => {
    h = await startHarness({
      faults: { silentSubscribe: EyeStatusUuids.Status }
    });
    const { device } = await h.connect();

    const seen = [];
    const unwatch = h.ble.watchEye(device.id, s => seen.push(s));

    expect(seen[0]).toEqual({ kind: EyeReadingKind.Waiting });
    await h.waitFor(() => seen[seen.length - 1] && seen[seen.length - 1].kind === EyeReadingKind.Failed);
    expect(seen[seen.length - 1]).toEqual({ kind: EyeReadingKind.Failed });
    unwatch();
  });

  test('an eye subscribe waits until an active board write completes', async () => {
    h = await startHarness();
    const { device } = await h.connect();

    const edited = { ...device, bootText: 'EYEWAIT' };
    const writeDone = h.writeConfiguration(device.id, edited);

    // Start watchEye right after the write starts; its CCC write must wait on _writeQueue.
    const seen = [];
    const unwatch = h.ble.watchEye(device.id, s => seen.push(s));

    await writeDone;
    await h.waitFor(() => seen.some(s => s && s.kind === EyeReadingKind.Reading));

    let lastObjectDataWriteIdx = -1;
    let firstEyeSubIdx = -1;
    h.radio.calls.forEach((call, idx) => {
      if (call.fn === 'mstWriteCharacteristic' && normalizeUuid(call.uuid) === OTS_UUID.OBJECT_DATA) {
        lastObjectDataWriteIdx = idx;
      }
      if (
        firstEyeSubIdx === -1 &&
        call.fn === 'mstPrepareNotificationChar' &&
        normalizeUuid(call.uuid) === EYE_UUID.STATUS
      ) {
        firstEyeSubIdx = idx;
      }
    });

    expect(lastObjectDataWriteIdx).toBeGreaterThan(-1);
    expect(firstEyeSubIdx).toBeGreaterThan(lastObjectDataWriteIdx);
    unwatch();
  });

  test('watchEye for a board that is not connected gives null', async () => {
    h = await startHarness();

    const seen = [];
    const unwatch = h.ble.watchEye('00000000-0000-0000-0000-000000009999', s => seen.push(s));
    unwatch();
    unwatch();

    expect(seen).toEqual([null]);
  });

  test('the mock board sends eye levels that move and stops 2 s after unwatch', async () => {
    h = await startHarness();
    const { device } = await h.connect({ id: MOCK_DEVICE_ID, name: 'Snapshot PB', isMock: true });
    expect(device.id).toBe(MOCK_DEVICE_ID);

    const levels = [];
    const unwatch = h.ble.watchEye(MOCK_DEVICE_ID, (s) => {
      if (s && s.kind === EyeReadingKind.Reading) levels.push(s.reading.levelRaw);
    });

    await h.waitFor(() => new Set(levels).size >= 3);
    unwatch();

    await new Promise(resolve => setTimeout(resolve, 2500));
    const countAfterStop = levels.length;
    await new Promise(resolve => setTimeout(resolve, 500));
    expect(levels.length).toBe(countAfterStop);
  });

  test('simulatedEyeLevel stays in 400..3700 and moves up then down', () => {
    const values = [];
    for (let tick = 0; tick <= 120; tick++) {
      const level = simulatedEyeLevel(tick);
      expect(level).toBeGreaterThanOrEqual(400);
      expect(level).toBeLessThanOrEqual(3700);
      values.push(level);
    }

    expect(values[0]).toBe(400);
    expect(values[1]).toBe(460);
    expect(values[55]).toBe(3700);
    expect(values[56]).toBe(3640);
    expect(values[110]).toBe(400);
    expect(values[111]).toBe(460);
  });
});
