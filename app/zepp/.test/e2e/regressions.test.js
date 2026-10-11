// Regressions: behaviours this harness found BROKEN on the pre-merge watch code
// and that master has since fixed. The tests were characterizations of the bugs;
// they now assert the correct behaviour, so the fixes stay fixed.
//
// All three had one root cause: the domain device `id` is the board's protobuf
// Device.device_id, while the BLE layer is addressed by MAC. BleManager now
// tracks BOTH (connectedAddress + connectedBoardId), isConnected() accepts
// either, and writes reuse the live connection's prepared OtsClient instead of
// re-deriving an address from the store key.

import { afterEach, beforeEach, describe, expect, test } from 'vitest';
import { startHarness } from '../harness';
import { connectThroughPages, LABELS, PAGES, seedConflict, syncNewBoard } from '../harness/flows';

describe('regressions', () => {
  let h;

  beforeEach(async () => {
    h = await startHarness();
  });

  afterEach(() => {
    if (h) h.teardown();
    h = null;
  });

  test('device_config reports the link as connected after a real connect', async () => {
    // Previously the page asked bleManager.isConnected(this.device.id) — a board
    // UUID — while the manager only knew the MAC, so it always showed
    // "BLE Disconnected" and the Save button never appeared.
    await syncNewBoard(h);

    expect(h.ble.isConnected(h.address)).toBe(true);
    expect(h.ble.isConnected(h.deviceUuid())).toBe(true);
    expect(h.hasText('Connected via BLE')).toBe(true);
    expect(h.hasText(LABELS.bleDisconnected)).toBe(false);
    expect(h.hasText(LABELS.saveToBoard)).toBe(true);
  });

  test('leaving device_config releases the BLE link', async () => {
    // Same mismatch in onDestroy: the isConnected guard never passed, so the
    // link handed over by the connecting page was left open.
    await syncNewBoard(h);
    expect(h.stack[h.stack.length - 1].__url).toBe(PAGES.deviceConfig);

    const before = h.radio.callsTo('mstDisconnect').length;
    h.back();

    expect(h.radio.callsTo('mstDisconnect').length).toBeGreaterThan(before);
    expect(h.radio.isConnected(h.address)).toBe(false);
  });

  test('"Keep My Values" actually writes the watch\'s values to the board', async () => {
    // Previously syncCoordinator passed the DOMAIN id to
    // bleManager.writeDeviceConfiguration, which handed it to OtsClient as a
    // GATT address — so the write went to an address that does not exist and the
    // board never received it. Writes now reuse the connection's OtsClient.
    await seedConflict(h, state => {
      state.deviceConfig.bootText = 'BOARDTXT';
    });
    expect(await connectThroughPages(h)).toBe('Conflict');

    h.click(LABELS.keepMine);
    await h.waitFor(() => h.router.urls().includes(`replace:${PAGES.deviceConfig}`));

    // The watch's value reached the board.
    expect(h.board.state.deviceConfig.bootText).toBe('SNAPSHOT');
    expect(h.board.appliedWrites.map(w => w.name)).toContain('device_config');
    expect(h.store.getKnownDevices()[0].bootText).toBe('SNAPSHOT');
  });
});
