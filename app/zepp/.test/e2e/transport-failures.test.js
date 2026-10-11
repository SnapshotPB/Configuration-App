// Fault injection: every way the transport can let the app down, and what the
// connecting page shows when it does. Also the transport variations the board
// firmware or the BLE stack might legitimately present.

import { afterEach, describe, expect, test } from 'vitest';
import { startHarness } from '../harness';
import { LABELS, PAGES, tapDiscoveredBoard } from '../harness/flows';

describe('transport failures', () => {
  let h;

  afterEach(() => {
    // h is assigned per-test; guard so a failure before startHarness reports the
    // real error rather than a TypeError in the hook.
    if (h) h.teardown();
    h = null;
  });

  test('a refused connection fails step 1', async () => {
    h = await startHarness({ faults: { connect: 'fail' } });
    await tapDiscoveredBoard(h);

    await h.waitForText('Connection Failed');
    expect(h.ble.connectedAddress).toBeNull();
    expect(h.ble.connectingAddress).toBeNull();
  });

  test('failed service discovery fails step 2 and releases the link', async () => {
    h = await startHarness({ faults: { buildProfile: 'fail' } });
    await tapDiscoveredBoard(h);

    await h.waitForText('Service discovery failed');
    // The link was already up when discovery failed, so it must be torn down.
    expect(h.radio.callsTo('mstDisconnect').length).toBeGreaterThan(0);
    expect(h.radio.isConnected(h.address)).toBe(false);
  });

  test('an OLCP walk that never succeeds reports the missing object', async () => {
    h = await startHarness({ faults: { olcpResult: 0x05 } }); // Out Of Bounds
    await tapDiscoveredBoard(h);

    await h.waitForText("OTS object 'device' not found");
    expect(h.radio.isConnected(h.address)).toBe(false);
  });

  test('a non-success OACP result surfaces the result code', async () => {
    h = await startHarness({ faults: { oacpResult: 0x0a } }); // Operation Failed
    await tapDiscoveredBoard(h);

    await h.waitForText('OACP READ handshake failed: code 10');
  });

  test('a control point that never indicates times out', async () => {
    h = await startHarness({ faults: { silentOacp: true } });
    await tapDiscoveredBoard(h);

    await h.waitForText('Control point indication for 2AC5 timed out');
    expect(h.radio.isConnected(h.address)).toBe(false);
  });

  test('a truncated content transfer times out instead of decoding garbage', async () => {
    h = await startHarness({ faults: { dropDataChunksAfter: 1 } });
    await tapDiscoveredBoard(h);

    await h.waitForText('OTS Content transfer timed out');
    expect(h.store.getKnownDevices()).toHaveLength(0);
  });

  test('a failed characteristic read is reported, not swallowed', async () => {
    h = await startHarness({ faults: { readError: '2AC0' } }); // Object Size
    await tapDiscoveredBoard(h);

    await h.waitForText('Read char 2AC0 failed');
  });

  test('works when the stack reports short 16-bit UUIDs instead of canonical ones', async () => {
    // OtsClient matches UUIDs by substring in either direction precisely so it
    // survives both reporting styles; the default harness uses canonical.
    h = await startHarness({ uuidFormat: 'short' });
    const { device } = await h.connect();
    expect(device.id).toBe(h.deviceUuid());
  });

  test('works when the board splits content into smaller notifications', async () => {
    h = await startHarness({ boardOptions: { chunkSize: 7 } });
    const { device } = await h.connect();
    expect(device.knownProfiles).toHaveLength(2);
  });

  test('works when the firmware does not pad objects at all', async () => {
    h = await startHarness({ boardOptions: { padAlignment: 0 } });
    const { device } = await h.connect();
    expect(device.bootText).toBe('SNAPSHOT');
  });

  test('a continuous advertisement stream is deduped into one card, with no redraw storm', async () => {
    // A real BLE Central re-reports the same peripheral many times a second.
    // BleManager dedupes by id and only then invokes the scan callback, which is
    // what stops device_list from tearing down and rebuilding its whole widget
    // list on every advertisement.
    h = await startHarness({ advertIntervalMs: 20 });
    h.open(PAGES.deviceList);
    h.click(LABELS.scan);
    await h.waitForText(LABELS.discovered);

    const firstCard = h.ui.findByText(LABELS.discovered);
    await new Promise(resolve => setTimeout(resolve, 400));

    const cards = h.ui.findAll(
      w => typeof w.props.text === 'string' && w.props.text.includes(LABELS.discovered)
    );
    expect(cards).toHaveLength(1);
    // Same widget instance: the repeated adverts caused no redraw at all.
    expect(cards[0].id).toBe(firstCard.id);
  });
});
