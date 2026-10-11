// Disconnect and teardown: releasing the GATT link, and what the board forgets
// when the link drops.

import { afterEach, beforeEach, describe, expect, test } from 'vitest';
import { startHarness } from '../harness';
import { connectThroughPages, LABELS } from '../harness/flows';

/** CCCD writes the APP issued to turn a subscription off. */
function unsubscribesIssuedByApp(h) {
  return h.radio.calls.filter(
    c =>
      (c.fn === 'mstPrepareNotificationChar' || c.fn === 'mstPrepareIndicateChar') &&
      c.enable === false
  );
}

describe('disconnect and teardown', () => {
  let h;

  beforeEach(async () => {
    h = await startHarness();
  });

  afterEach(() => {
    h.teardown();
  });

  test('disconnect releases the link and closes the OTS client without late CCCD writes', async () => {
    await h.connect();
    expect(h.ble.isConnected(h.address)).toBe(true);
    expect(h.radio.isConnected(h.address)).toBe(true);
    expect(h.board.selectedIndex).toBeGreaterThanOrEqual(0);
    const client = h.ble._otsClient;
    const unsubscribesBefore = unsubscribesIssuedByApp(h).length;

    await new Promise(resolve => h.ble.disconnect(resolve));
    // close() is fire-and-forget, so give it time to send anything it would send.
    await new Promise(resolve => setTimeout(resolve, 200));

    expect(h.ble.isConnected(h.address)).toBe(false);
    expect(h.radio.isConnected(h.address)).toBe(false);

    // The subscriptions end with the link. A CCCD-disable write sent after
    // mstDisconnect could reach a new link to the same address and turn off its
    // indications, so the app sends none.
    expect(unsubscribesIssuedByApp(h).length).toBe(unsubscribesBefore);

    // The closed client refuses every later GATT op.
    expect(client.closed).toBe(true);
    await expect(client._readChar('2AC0')).rejects.toThrow('OtsClient is closed');
  });

  test('a GATT op queued behind a close is refused, not sent', async () => {
    await h.connect();
    const client = h.ble._otsClient;
    const readsBefore = h.radio.callsTo('mstReadCharacteristic').length;

    // Queue a read, then close before the queue reaches it.
    const queued = client._readChar('2AC0');
    queued.catch(() => {});
    const closing = client.close({ linkDropped: true });

    await closing;
    await expect(queued).rejects.toThrow();
    expect(h.radio.callsTo('mstReadCharacteristic').length).toBe(readsBefore);
  });

  test('board model: the firmware drops its OTS session with the link', async () => {
    // A check on the VIRTUAL BOARD, not on the app: VirtualRadio.disconnect()
    // resets the session unconditionally. Kept so a change to the board model
    // that stopped clearing state would be caught.
    await h.connect();
    expect(h.board.selectedIndex).toBeGreaterThanOrEqual(0);

    await new Promise(resolve => h.ble.disconnect(resolve));

    expect(h.board.selectedIndex).toBe(-1);
    expect(h.board.subscriptions.size).toBe(0);
    expect(h.board.pendingWrite).toBeNull();
  });

  test('GATT operations after a disconnect fail rather than silently succeeding', async () => {
    const { device } = await h.connect();
    await new Promise(resolve => h.ble.disconnect(resolve));

    await expect(h.writeConfiguration(h.address, device)).rejects.toThrow();
    expect(h.board.appliedWrites).toHaveLength(0);
  });

  test('reconnecting after a disconnect works from a clean session', async () => {
    await h.connect();
    await new Promise(resolve => h.ble.disconnect(resolve));

    const { device } = await h.connect();
    expect(device.id).toBe(h.deviceUuid());
    expect(h.board.droppedNotifications).toEqual([]);
  });

  test('leaving the connecting page without a handoff releases the link', async () => {
    await connectThroughPages(h);

    const before = h.radio.callsTo('mstDisconnect').length;
    h.click(LABELS.ignore);
    expect(h.radio.callsTo('mstDisconnect').length).toBeGreaterThan(before);
  });
});
