// Cancelling a connect mid-handshake. BleManager guards the flow with
// _connectGen so a link that comes up AFTER the user backed out is released
// rather than driven; the virtual radio deliberately does not abort a pending
// connect on disconnect, so that race is reproducible here.
//
// This file gets its own module scope because a cancelled flow can leave the
// app's BLE promises in flight; Vitest isolates test files, so nothing leaks
// into the other suites.

import { afterEach, describe, expect, test } from 'vitest';
import { startHarness } from '../harness';
import { LABELS, PAGES, STEP, tapDiscoveredBoard } from '../harness/flows';

describe('connect cancellation', () => {
  let h;

  afterEach(() => {
    // h is assigned per-test; guard so a failure before startHarness reports the
    // real error rather than a TypeError in the hook.
    if (h) h.teardown();
    h = null;
  });

  test('backing out mid-handshake releases the late link and leaves nothing connected', async () => {
    h = await startHarness({ faults: { connectDelayMs: 2000 } });
    await tapDiscoveredBoard(h);
    await h.waitForText(STEP.inProgress('Connection'));

    h.click(LABELS.cancel);

    // Page torn down, and the in-flight link cancelled.
    expect(h.stack.map(p => p.__url)).toEqual([PAGES.deviceList]);
    expect(h.ble.connectedAddress).toBeNull();
    expect(h.ble.connectingAddress).toBeNull();

    // The link still comes up afterwards; BleManager must drop it.
    await h.waitFor(() => h.radio.callsTo('mstDisconnect').length >= 2, {
      message: 'the late link was never released'
    });

    expect(h.ble.connectedAddress).toBeNull();
    expect(h.radio.isConnected(h.address)).toBe(false);
    // The handshake never got as far as service discovery or any OTS traffic.
    expect(h.radio.callsTo('mstBuildProfile')).toHaveLength(0);
    expect(h.radio.callsTo('mstReadCharacteristic')).toHaveLength(0);
    expect(h.board.selectedIndex).toBe(-1);
  });

  test('a cancelled page stops redrawing when progress callbacks arrive late', async () => {
    h = await startHarness({ faults: { connectDelayMs: 2000 } });
    await tapDiscoveredBoard(h);
    await h.waitForText(STEP.inProgress('Connection'));

    h.click(LABELS.cancel);
    const afterCancel = h.texts();

    await h.waitFor(() => h.radio.callsTo('mstDisconnect').length >= 2);

    // Nothing from the dead page reappeared on the device list.
    expect(h.texts()).toEqual(afterCancel);
    expect(h.hasText('Connection Failed')).toBe(false);
  });

  test('a connect that never completes leaves the page on step 1', async () => {
    h = await startHarness({ faults: { connect: 'silent' } });
    await tapDiscoveredBoard(h);
    await h.waitForText(STEP.inProgress('Connection'));

    expect(h.hasText(STEP.pending('Services'))).toBe(true);
    expect(h.ble.connectingAddress).toBe(h.address);
  });
});
