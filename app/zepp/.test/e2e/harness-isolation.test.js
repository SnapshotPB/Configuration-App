// Tests of the harness itself.
//
// OtsClient registers its mstOnCharaValueArrived / mstOnCharaNotification
// callbacks ONCE at module load, and the mock never clears them — so a radio
// that keeps running after teardown delivers straight into the next test in the
// same file. A stale 2AC0 read or 2AC5 indication can satisfy an in-flight
// _readChar/_awaitControlResponse and make a test pass for the wrong reason.
// That is the worst failure mode a harness can have, so it is pinned here.

import { afterEach, describe, expect, test } from 'vitest';
import { startHarness } from '../harness';
import * as ble from '../mocks/zos-ble';

describe('harness isolation', () => {
  let h;

  afterEach(() => {
    if (h) h.teardown();
    h = null;
  });

  test('teardown stops the radio: no event reaches the app after it', async () => {
    h = await startHarness();
    await h.connect();

    const delivered = [];
    ble.mstOnCharaValueArrived(res => delivered.push(res.characteristicUuid));

    // Issue a read, then tear down before the radio's scheduled delivery runs.
    ble.mstReadCharacteristic({
      address: h.address,
      serviceUuid: '1825',
      characteristicUuid: '2ABD',
      fail: () => {}
    });
    h.teardown();
    const radio = h.radio;
    h = null;

    await new Promise(resolve => setTimeout(resolve, 50));

    expect(delivered).toEqual([]);
    expect(radio.detached).toBe(true);
    expect(radio.timers.size).toBe(0);
  });

  test('a mst* call with no radio installed fails instead of hanging', async () => {
    // BleManager.disconnect only invokes its callback from success/fail, so a
    // mock that silently no-ops would hang to the vitest timeout.
    h = await startHarness();
    await h.connect();
    h.teardown();

    await new Promise(resolve => h.ble.disconnect(resolve));
    h = null;
  });

  test('each harness gets its own faults, so a shared fixture is not mutated', async () => {
    const faults = { abortPendingConnectOnDisconnect: true };

    h = await startHarness({ faults });
    h.ble.disconnect();
    h.teardown();
    h = null;

    // The radio records the abort on itself, never on the caller's object.
    expect(faults).toEqual({ abortPendingConnectOnDisconnect: true });
  });

  test('px() and the reported screen size stay consistent', async () => {
    h = await startHarness();
    // 480 design width, 480 device: identity. The point is that they agree.
    const { px, screenWidth } = await import('../../src/utils/px.js');
    expect(screenWidth()).toBe(480);
    expect(px(100)).toBe(100);
  });

  test('the runtime has no TextDecoder or TextEncoder, as on the watch', async () => {
    h = await startHarness();
    // App code that used these without a guard passed in Node and threw on the watch.
    expect(typeof globalThis.TextDecoder).toBe('undefined');
    expect(typeof globalThis.TextEncoder).toBe('undefined');
  });

  test('OTS object names decode as ASCII with the NUL padding removed', async () => {
    h = await startHarness();
    const { decodeObjectName } = await import('../../src/bluetooth/OtsClient.js');
    const bytes = new Uint8Array([0x64, 0x65, 0x76, 0x69, 0x63, 0x65, 0x00, 0x00]);
    expect(decodeObjectName(bytes)).toBe('device');
    expect(decodeObjectName(new Uint8Array(0))).toBe('');
  });
});
