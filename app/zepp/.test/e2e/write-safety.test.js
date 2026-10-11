// Board write safety: a GATT op that never settles, two writes at the same
// time, a link that drops during a save, and the "Keep My Values" write states
// on the connecting page.

import { afterEach, describe, expect, test } from 'vitest';
import { startHarness } from '../harness';
import { connectThroughPages, LABELS, PAGES, seedConflict, syncNewBoard } from '../harness/flows';
import { createBoardState } from '../virtual-board/boardState';

const OBJECT_DATA_UUID = '00000002-5350-4221-0000-5350425F4F54';

describe('board write safety', () => {
  let h;

  afterEach(() => {
    if (h) h.teardown();
    h = null;
  });

  test('a dropped chunk write times out, and the link is treated as lost', async () => {
    h = await startHarness();
    const { device } = await h.connect();
    h.faults.silentWrite = OBJECT_DATA_UUID;

    const error = await h.writeConfiguration(h.address, device).then(
      () => null,
      err => err
    );

    expect(error).not.toBeNull();
    expect(error.message).toMatch(/Write char .* timed out/);
    expect(error.linkLost).toBe(true);
    // The app released the link, so the next connect starts clean.
    expect(h.ble.isConnected(h.address)).toBe(false);
    expect(h.radio.isConnected(h.address)).toBe(false);
  });

  test('the GATT queue watchdog rejects an op that never settles, and the queue moves on', async () => {
    h = await startHarness();
    await h.connect();
    const client = h.ble._otsClient;

    const hung = client._serialize(() => new Promise(() => {}));
    await expect(hung).rejects.toThrow(/did not settle/);

    // The next op runs normally.
    const size = await client._readChar('2AC0');
    expect(size.length).toBeGreaterThan(0);
  });

  test('two writes started together run one after the other', async () => {
    h = await startHarness();
    const { device } = await h.connect();
    const first = JSON.parse(JSON.stringify(device));
    first.bootText = 'FIRST';
    const second = JSON.parse(JSON.stringify(device));
    second.bootText = 'SECOND';

    await Promise.all([
      h.writeConfiguration(h.address, first),
      h.writeConfiguration(h.address, second)
    ]);

    // Each write selects and writes device_config, then profiles. Interleaved
    // selections would put one object's bytes into the other.
    expect(h.board.appliedWrites.map(w => w.name)).toEqual([
      'device_config',
      'profiles',
      'device_config',
      'profiles'
    ]);
    expect(h.board.appliedWrites.every(w => w.error === null)).toBe(true);
    expect(h.board.state.deviceConfig.bootText).toBe('SECOND');
  });

  test('"Keep My Values" hides the conflict buttons while the write runs', async () => {
    h = await startHarness();
    await seedConflict(h, state => {
      state.deviceConfig.bootText = 'BOARDTXT';
    });
    expect(await connectThroughPages(h)).toBe('Conflict');

    h.click(LABELS.keepMine);

    expect(h.hasText('Writing your values')).toBe(true);
    expect(h.hasText(LABELS.keepMine)).toBe(false);
    expect(h.hasText(LABELS.useBoard)).toBe(false);

    await h.waitFor(() => h.router.urls().includes(`replace:${PAGES.deviceConfig}`));
    expect(h.board.state.deviceConfig.bootText).toBe('SNAPSHOT');
  });

  test('a failed "Keep My Values" write shows the error and a retry that works', async () => {
    h = await startHarness();
    await seedConflict(h, state => {
      state.deviceConfig.bootText = 'BOARDTXT';
    });
    expect(await connectThroughPages(h)).toBe('Conflict');
    h.faults.writeError = OBJECT_DATA_UUID;

    h.click(LABELS.keepMine);
    await h.waitForText('Write Failed');
    expect(h.hasText('unsaved changes')).toBe(true);
    // The page stays, so the user sees the failure.
    expect(h.router.urls()).not.toContain(`replace:${PAGES.deviceConfig}`);

    delete h.faults.writeError;
    h.click('Retry');
    await h.waitFor(() => h.router.urls().includes(`replace:${PAGES.deviceConfig}`));
    expect(h.board.state.deviceConfig.bootText).toBe('SNAPSHOT');
  });

  test('"Keep My Values" on a board in lockout keeps the values unsaved and sends no write', async () => {
    h = await startHarness();
    // The watch needs a local edit: with no local edit, the read takes the board values.
    await seedConflict(h, state => {
      state.deviceConfig.bootText = 'BOARDTXT';
      state.device.lockoutEnabled = true;
    });
    expect(await connectThroughPages(h)).toBe('Conflict');

    h.click(LABELS.keepMine);
    await h.waitForText('Write Failed');
    expect(h.hasText(LABELS.lockout)).toBe(true);
    expect(h.hasText('unsaved changes')).toBe(true);
    // A retry can not work until the user turns off the lockout, so only Continue shows.
    expect(h.hasText('Retry')).toBe(false);
    expect(h.board.appliedWrites).toHaveLength(0);
    expect(h.board.state.deviceConfig.bootText).toBe('BOARDTXT');
    expect(h.store.isDiverged(h.store.getKnownDevices()[0].id)).toBe(true);
  });

  test('a save that loses the link shows the device as disconnected', async () => {
    h = await startHarness();
    await syncNewBoard(h);
    expect(h.stack[h.stack.length - 1].__url).toBe(PAGES.deviceConfig);
    // Make a local edit, so the save has something to write.
    const local = JSON.parse(JSON.stringify(h.store.getKnownDevices()[0]));
    local.bootText = 'EDITED';
    h.store.update([local]);
    h.faults.silentWrite = OBJECT_DATA_UUID;

    h.click(LABELS.saveToBoard);
    await h.waitForText(LABELS.bleDisconnected);

    expect(h.ble.isConnected(h.address)).toBe(false);
    expect(h.logs().some(line => line.includes('treating the link as lost'))).toBe(true);
    // The edit was not written, so it stays unsaved.
    expect(h.store.isDiverged(local.id)).toBe(true);
  });

  test('a board in lockout shows why Save is missing, and sends no write', async () => {
    // device.proto: a board in lockout does not accept a change until the user turns off the
    // lockout switch on the board.
    h = await startHarness({ state: createBoardState({ device: { lockoutEnabled: true } }) });
    await syncNewBoard(h);
    expect(h.stack[h.stack.length - 1].__url).toBe(PAGES.deviceConfig);
    expect(h.hasText(LABELS.saveToBoard)).toBe(false);
    expect(h.hasText(LABELS.lockout)).toBe(true);

    // An edit of the active profile makes the profile editor offer a save.
    const local = JSON.parse(JSON.stringify(h.store.getKnownDevices()[0]));
    local.knownProfiles.find(p => p.id === local.activeProfileId).searSolenoidDwell += 5;
    h.store.update([local]);
    h.click(LABELS.tuneProfile);
    expect(h.stack[h.stack.length - 1].__url).toBe(PAGES.profileEdit);
    expect(h.hasText(LABELS.saveToBoard)).toBe(false);
    expect(h.hasText(LABELS.lockout)).toBe(true);

    // A save request that reaches the page anyway starts no write. The page sets `saving` before
    // it starts a write, so the flag stays false only when no write starts.
    const editor = h.stack[h.stack.length - 1];
    editor.saveToBoard();
    expect(editor.saving).toBe(false);
    expect(h.board.appliedWrites).toHaveLength(0);
    expect(h.store.isDiverged(local.id)).toBe(true);
  });

  test('a reconnect from device_config does not stack a second device_config', async () => {
    h = await startHarness();
    await syncNewBoard(h);
    h.click('Tap to Disconnect');
    await h.waitForText(LABELS.bleDisconnected);

    h.click('Tap to Connect');
    await h.waitForText(LABELS.proceed);
    h.click(LABELS.proceed);

    const configPages = h.stack.filter(page => page.__url === PAGES.deviceConfig);
    expect(configPages).toHaveLength(1);
    expect(h.stack[h.stack.length - 1].__url).toBe(PAGES.deviceConfig);
  });

  test('profile cards show readable fire modes and no NaN dwell', async () => {
    h = await startHarness();
    await syncNewBoard(h);
    // A profile stored without a sear dwell.
    const local = JSON.parse(JSON.stringify(h.store.getKnownDevices()[0]));
    delete local.knownProfiles[1].searSolenoidDwell;
    h.store.update([local]);

    h.click(local.knownProfiles.find(p => p.id === local.activeProfileId).name);
    expect(h.stack[h.stack.length - 1].__url).toBe(PAGES.profileSelection);

    const texts = h.texts().join('\n');
    expect(texts).not.toMatch(/NaN/);
    // No card shows the stored name of a fire mode.
    expect(texts).not.toMatch(/RAMPING_CUSTOM|CUSTOM_RAMPING|RAMPING_NXL/);
    expect(texts).toMatch(/Mode: NXL 2026 \/ Dwell: --/);
    expect(texts).toMatch(/Mode: Semi \/ Dwell: 5\.5 ms/);
  });
});
