// Tests for the eye GATT service in mstBuildProfile and the OTS-only fallback.

import { afterEach, describe, expect, test } from 'vitest';
import { startHarness } from '../harness';
import { EyeStatusUuids } from '../../src/bluetooth/eyeStatus';

function buildServiceUuids(radio) {
  return radio.callsTo('mstBuildProfile').map(c => (c.services || []).map(s => s.uuid));
}

describe('eye GATT profile and OTS fallback', () => {
  let h;

  afterEach(() => {
    if (h) h.teardown();
    h = null;
  });

  test('a new board builds one profile with OTS and the eye service', async () => {
    h = await startHarness();
    const { device } = await h.connect();

    expect(device.id).toBe(h.deviceUuid());
    expect(buildServiceUuids(h.radio)).toEqual([['1825', EyeStatusUuids.Service]]);
    expect(h.ble.eyeServiceSupported).toBe(true);
  });

  test('an old board with missingService fail falls back to OTS only and caches the address', async () => {
    h = await startHarness({
      boardOptions: { eyeService: false },
      faults: { missingService: 'fail' }
    });
    const { device } = await h.connect();

    expect(device.id).toBe(h.deviceUuid());
    expect(buildServiceUuids(h.radio)).toEqual([
      ['1825', EyeStatusUuids.Service],
      ['1825']
    ]);
    expect(h.ble.eyeServiceSupported).toBe(false);

    // Disconnect clears eyeServiceSupported; the next connect builds OTS only at once.
    await new Promise(resolve => h.ble.disconnect(resolve));
    expect(h.ble.eyeServiceSupported).toBeNull();

    const buildsBefore = h.radio.callsTo('mstBuildProfile').length;
    const second = await h.connect();
    expect(second.device.id).toBe(h.deviceUuid());
    const secondBuilds = buildServiceUuids(h.radio).slice(buildsBefore);
    expect(secondBuilds).toEqual([['1825']]);
    expect(h.ble.eyeServiceSupported).toBe(false);
  });

  test('an old board with missingService break rebuilds with OTS only after prepare fails', async () => {
    h = await startHarness({
      boardOptions: { eyeService: false },
      faults: { missingService: 'break' }
    });
    const { device } = await h.connect();

    expect(device.id).toBe(h.deviceUuid());
    expect(buildServiceUuids(h.radio)).toEqual([
      ['1825', EyeStatusUuids.Service],
      ['1825']
    ]);
    expect(h.ble.eyeServiceSupported).toBe(false);
  });

  test('an old board with missingService ignore keeps the full profile for connect', async () => {
    h = await startHarness({
      boardOptions: { eyeService: false },
      faults: { missingService: 'ignore' }
    });
    const { device } = await h.connect();

    expect(device.id).toBe(h.deviceUuid());
    expect(buildServiceUuids(h.radio)).toEqual([['1825', EyeStatusUuids.Service]]);
    expect(h.ble.eyeServiceSupported).toBe(true);
  });

  test('buildProfile fail tries both profiles, fails step 2, and releases the link', async () => {
    h = await startHarness({ faults: { buildProfile: 'fail' } });

    let err = null;
    try {
      await h.connect();
    } catch (e) {
      err = e;
    }

    expect(err).not.toBeNull();
    expect(err.message).toBe('Service discovery failed');
    const lastProgress = err.progress[err.progress.length - 1];
    expect(lastProgress.step2).toBe(h.StepStatus.FAILED);
    expect(buildServiceUuids(h.radio)).toEqual([
      ['1825', EyeStatusUuids.Service],
      ['1825']
    ]);
    expect(h.radio.isConnected(h.address)).toBe(false);
    expect(h.ble.eyeServiceSupported).toBeNull();
  });

  test('a disconnect during the first profile build starts no fallback build', async () => {
    h = await startHarness({
      boardOptions: { eyeService: false },
      faults: { missingService: 'fail' },
      latency: { gatt: 1000 }
    });

    const attempt = h.connect();
    attempt.catch(() => {});

    await h.waitFor(() => h.radio.callsTo('mstBuildProfile').length === 1);
    await new Promise(resolve => h.ble.disconnect(resolve));
    await new Promise(resolve => setTimeout(resolve, 2000));

    expect(buildServiceUuids(h.radio)).toEqual([['1825', EyeStatusUuids.Service]]);
    expect(h.ble.eyeServiceSupported).toBeNull();
  });
});
