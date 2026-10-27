// DeviceStore load and save rules: defaults for absent profile fields, a
// corrupt store, and the deferred flash write of the stepper page.

import { afterEach, beforeEach, describe, expect, test } from 'vitest';
import { startHarness } from '../harness';
import { storageRecorder } from '../mocks/zos-storage';

const STORAGE_KEY = 'snapshot_pb_devices';
const CORRUPT_BACKUP_KEY = 'snapshot_pb_devices_corrupt';

describe('device store safety', () => {
  let h;
  let DeviceStore;

  beforeEach(async () => {
    h = await startHarness();
    ({ DeviceStore } = await import('../../src/store/DeviceStore.js'));
  });

  afterEach(() => {
    if (h) h.teardown();
    h = null;
  });

  test('a profile stored before a field existed gets the MarkerProfile default', () => {
    const legacy = [{
      id: 'dev-1',
      name: 'Old',
      bootText: 'OLD',
      knownProfiles: [{ id: 'p-1', name: 'Legacy', fireMode: 'SEMI', fireRateCapTenths: 105 }]
    }];
    storageRecorder.raw().set(STORAGE_KEY, JSON.stringify(legacy));

    const profile = new DeviceStore().getKnownDevices()[0].knownProfiles[0];

    expect(profile.cockingSolenoidTimeout).toBe(5000);
    expect(profile.triggerDebounceOn).toBe(20);
    expect(profile.searSolenoidDwell).toBe(45);
    expect(profile.searSolenoidDelay).toBe(60);
    expect(profile.cockingSolenoidDwellClosed).toBe(300);
    expect(profile.eyeMode).toBe('OFF');
    // A stored value is kept.
    expect(profile.fireMode).toBe('SEMI');
    expect(profile.name).toBe('Legacy');
  });

  test('a corrupt store is backed up, and the backup is not overwritten', () => {
    storageRecorder.raw().set(STORAGE_KEY, '{not json');

    expect(new DeviceStore().getKnownDevices()).toEqual([]);
    expect(storageRecorder.raw().get(CORRUPT_BACKUP_KEY)).toBe('{not json');
    expect(h.logs().some(line => line.includes('corrupt'))).toBe(true);

    // A second corrupt load keeps the first backup.
    storageRecorder.raw().set(STORAGE_KEY, '"a string, not a list"');
    expect(new DeviceStore().getKnownDevices()).toEqual([]);
    expect(storageRecorder.raw().get(CORRUPT_BACKUP_KEY)).toBe('{not json');
  });

  test('a deferred profile field change reaches flash on flush, not on each tap', () => {
    const store = new DeviceStore();
    store.update([{ id: 'dev-1', name: 'D', knownProfiles: [{ id: 'p-1', name: 'P', eyeDebounce: 10 }] }]);
    const before = storageRecorder.raw().get(STORAGE_KEY);

    store.updateProfileField('dev-1', 'p-1', 'eyeDebounce', 11, { defer: true });
    store.updateProfileField('dev-1', 'p-1', 'eyeDebounce', 12, { defer: true });

    // Readers see the change at once; flash does not yet.
    expect(store.getKnownDevices()[0].knownProfiles[0].eyeDebounce).toBe(12);
    expect(storageRecorder.raw().get(STORAGE_KEY)).toBe(before);

    store.flush();
    expect(JSON.parse(storageRecorder.raw().get(STORAGE_KEY))[0].knownProfiles[0].eyeDebounce).toBe(12);
  });
});
