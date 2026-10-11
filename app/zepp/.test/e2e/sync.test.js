// Device sync: the NewDevice confirm path, and how a read is classified against
// the stored board baseline.
//
// The rule that shapes these tests: a read is compared against the BASELINE the
// watch recorded for the board (`boardState`), not against the local copy. So a
// local edit the user has not saved stays Synced and shows as unsaved. A board
// that moved out from under the watch raises a Conflict only when the watch also
// holds a local edit; with no local edits the watch takes the board values.

import { afterEach, describe, expect, test } from 'vitest';
import { startHarness } from '../harness';
import {
  connectThroughPages,
  LABELS,
  PAGES,
  registerKnownDevice,
  seedBoardDivergence,
  seedConflict,
  seedLocalEdit
} from '../harness/flows';

describe('device sync', () => {
  let h;

  afterEach(() => {
    if (h) h.teardown();
    h = null;
  });

  test('an unknown board is offered as a new device and persisted on confirm', async () => {
    h = await startHarness();
    expect(await connectThroughPages(h)).toBe('NewDevice');
    expect(h.store.getKnownDevices()).toHaveLength(0);

    h.click(LABELS.syncBoard);

    const stored = h.store.getKnownDevices();
    expect(stored).toHaveLength(1);
    expect(stored[0].photoPath).toBe('');
    // Confirming records the board baseline every later read is classified against.
    expect(stored[0].boardState).toBeDefined();
    expect(h.router.urls()).toContain(`replace:${PAGES.deviceConfig}`);
  });

  test('ignoring a new device leaves the store empty and goes back', async () => {
    h = await startHarness();
    await connectThroughPages(h);

    h.click(LABELS.ignore);

    expect(h.store.getKnownDevices()).toHaveLength(0);
    expect(h.router.last().type).toBe('back');
    // Back from the connecting page releases the link it owns.
    expect(h.radio.callsTo('mstDisconnect').length).toBeGreaterThan(0);
  });

  test('a board that changed under a local edit raises a conflict', async () => {
    h = await startHarness();
    await seedConflict(h, state => {
      state.deviceConfig.bootText = 'BOARDTXT';
    });

    expect(await connectThroughPages(h)).toBe('Conflict');
  });

  test('a board profile edited elsewhere under a local edit also raises a conflict', async () => {
    h = await startHarness();
    await seedConflict(h, state => {
      state.profiles.profiles[1].autococker.searSolenoidDwell = 99;
    });

    const { device } = await h.connect();
    expect(h.store.onDeviceRead(device).type).toBe('Conflict');
  });

  test('a board that changed with no local edits is taken with no prompt', async () => {
    // Issue #122: a change in the board menu raised a conflict although the
    // watch had nothing to lose, and "Keep My Values" wrote the old value back.
    h = await startHarness();
    const stored = await seedBoardDivergence(h, state => {
      state.deviceConfig.bootText = 'BOARDTXT';
      state.deviceConfig.screenBrightness = 3; // HIGH
      state.profiles.profiles[1].autococker.searSolenoidDwell = 99;
    });

    expect(await connectThroughPages(h)).toBe('Synced');

    const synced = h.store.getKnownDevices()[0];
    expect(synced.bootText).toBe('BOARDTXT');
    expect(synced.screenBrightness).toBe('HIGH');
    expect(synced.knownProfiles[1].searSolenoidDwell).toBe(99);
    expect(synced.boardState.bootText).toBe('BOARDTXT');
    expect(synced.boardState.screenBrightness).toBe('HIGH');
    // The user's own name survives, and nothing shows as unsaved.
    expect(synced.name).toBe('My Board');
    expect(h.store.isDiverged(stored.id)).toBe(false);
    // Nothing was written back to the board.
    expect(h.board.appliedWrites).toHaveLength(0);
  });

  test('Use Board Values takes the board state and keeps the user\'s own name', async () => {
    h = await startHarness();
    await seedConflict(h, state => {
      state.deviceConfig.bootText = 'BOARDTXT';
    });
    expect(await connectThroughPages(h)).toBe('Conflict');

    h.click(LABELS.useBoard);

    const resolved = h.store.getKnownDevices()[0];
    expect(resolved.bootText).toBe('BOARDTXT');
    // TakeDevice is a state-only update — the user's own name survives.
    expect(resolved.name).toBe('My Board');
    expect(h.router.urls()).toContain(`replace:${PAGES.deviceConfig}`);
  });

  test('an unsaved local edit stays Synced and is reported as diverged', async () => {
    // This is the rule that keeps the watch usable: without it an unsaved edit
    // raised a conflict on every reconnect and Discard never got a chance to run.
    h = await startHarness();
    const local = await seedLocalEdit(h, d => {
      d.bootText = 'LOCALTXT';
    });

    const { device } = await h.connect();
    expect(h.store.onDeviceRead(device).type).toBe('Synced');

    // The edit is preserved, and surfaced as pending rather than silently lost.
    expect(h.store.getKnownDevices()[0].bootText).toBe('LOCALTXT');
    expect(h.store.isDiverged(local.id)).toBe(true);
  });

  test('a registered board with no local edits reconciles clean', async () => {
    h = await startHarness();
    const stored = await registerKnownDevice(h);

    const { device } = await h.connect();
    expect(h.store.onDeviceRead(device).type).toBe('Synced');
    expect(h.store.isDiverged(stored.id)).toBe(false);
  });

  test('a stored placeholder mode moves to unknown on the first read after the update', async () => {
    // An older build stored MECHANICAL and OFF for a mode that it did not know.
    h = await startHarness();
    const stored = await registerKnownDevice(h);
    const legacy = JSON.parse(JSON.stringify(stored));
    [legacy.knownProfiles[0], legacy.boardState.knownProfiles[0]].forEach(p => {
      p.fireMode = 'MECHANICAL';
      p.eyeMode = 'OFF';
    });
    h.store.update([legacy]);
    h.board.state.profiles.profiles[0].autococker.fireMode = 42;
    h.board.state.profiles.profiles[0].autococker.eyeMode = 9;

    const { device } = await h.connect();
    expect(h.store.onDeviceRead(device).type).toBe('Synced');

    const migrated = h.store.getKnownDevices()[0];
    expect(migrated.knownProfiles[0].fireMode).toBe('UNKNOWN');
    expect(migrated.knownProfiles[0].eyeMode).toBe('UNKNOWN');
    expect(migrated.boardState.knownProfiles[0].fireMode).toBe('UNKNOWN');
    expect(h.store.isDiverged(migrated.id)).toBe(false);
  });

  test('Keep My Values on a stale unknown mode records the mode that the board keeps', async () => {
    // The stored copy says UNKNOWN, but the board now reports SEMI. The write sends the board's
    // own number, so the stored copy must record SEMI, or the next read raises the conflict again.
    h = await startHarness();
    await seedBoardDivergence(h, state => {
      state.profiles.profiles[0].autococker.fireMode = 42;
    });
    let { device } = await h.connect();
    // The watch has no local edit, so the read takes the board values with no prompt.
    expect(h.store.onDeviceRead(device).type).toBe('Synced');
    expect(h.store.getKnownDevices()[0].knownProfiles[0].fireMode).toBe('UNKNOWN');

    // A local edit makes the next board change a conflict. It leaves the fire mode UNKNOWN.
    const edited = JSON.parse(JSON.stringify(h.store.getKnownDevices()[0]));
    edited.knownProfiles[0].name = 'LOCALEDIT';
    h.store.update([edited]);

    h.board.state.profiles.profiles[0].autococker.fireMode = 2; // AUTOCOCKER_FIRE_MODE_SEMI
    ({ device } = await h.connect());
    expect(h.store.onDeviceRead(device).type).toBe('Conflict');

    const stored = h.store.getKnownDevices()[0];
    h.store.resolveConflict(stored.id, 'TakeLocal', device);

    expect(h.store.getKnownDevices()[0].knownProfiles[0].fireMode).toBe('SEMI');
  });
});
