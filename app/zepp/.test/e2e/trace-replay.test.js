// The recorded-trace seam. VirtualRadio records every client request with the
// responses it produced; a ScriptedBoard replays that script in place of the
// simulated firmware. Here the script is recorded from the virtual board and
// replayed against the same app code, which proves the seam works end to end —
// the same mechanism accepts a capture taken off real hardware.

import { afterEach, describe, expect, test } from 'vitest';
import { nextAddress, startHarness } from '../harness';
import { advertsFromCapture, ScriptedBoard } from '../virtual-board/trace';

describe('trace replay', () => {
  let harnesses = [];

  afterEach(() => {
    harnesses.forEach(h => h.teardown());
    harnesses = [];
  });

  test('a recorded GATT exchange replays into the same domain device', async () => {
    const recording = await startHarness();
    harnesses.push(recording);
    const { device: live } = await recording.connect();
    const script = recording.radio.script.map(entry => JSON.parse(JSON.stringify(entry)));
    recording.teardown();
    harnesses = [];

    expect(script.length).toBeGreaterThan(20);

    // A DIFFERENT address, per the one-board-per-harness rule in harness/index.js:
    // reusing the recording's address lets the recording harness's fire-and-forget
    // OtsClient.close() unsubscribes land on this radio at a live link.
    const scripted = new ScriptedBoard(script);
    const replayAddress = nextAddress();
    const replay = await startHarness({
      devices: [{ address: replayAddress, name: 'Snapshot PB', board: scripted }]
    });
    harnesses.push(replay);

    const { device: replayed } = await replay.connect({ id: replayAddress, name: 'Snapshot PB' });

    expect(scripted.divergence).toBeNull();
    // lastSeen is stamped at read time and mac is the BLE address this run used,
    // so both are compared separately from the decoded board state.
    expect({ ...replayed, lastSeen: null, mac: null }).toEqual({ ...live, lastSeen: null, mac: null });
    expect(replayed.mac).toBe(replayAddress);
    expect(live.mac).toBe(recording.address);
  });

  test('a script that does not match the client is reported, not silently wrong', async () => {
    const recording = await startHarness();
    harnesses.push(recording);
    await recording.connect();
    const script = recording.radio.script.map(entry => JSON.parse(JSON.stringify(entry)));
    recording.teardown();
    harnesses = [];

    // Drop the first recorded read so the replay falls out of step.
    const broken = script.filter((entry, index) => index !== script.findIndex(e => e.request.fn === 'mstReadCharacteristic'));
    const scripted = new ScriptedBoard(broken);
    const replayAddress = nextAddress();
    const replay = await startHarness({
      devices: [{ address: replayAddress, name: 'Snapshot PB', board: scripted }]
    });
    harnesses.push(replay);

    await expect(replay.connect({ id: replayAddress, name: 'Snapshot PB' })).rejects.toThrow();
    expect(scripted.divergence).toMatch(/script diverged/);
  });

  test('captured scan log lines replay as advertisements', () => {
    // The exact shape BleManager.js:99 logs today.
    const captured = [
      'Discovered device over BLE: {"dev_id":"C0:FF:EE:00:00:01","address":"C0:FF:EE:00:00:01","name":"Snapshot PB","rssi":-61,"service_data":[]}',
      { dev_id: 'C0:FF:EE:00:00:02', name: 'Something Else', rssi: -90 }
    ];

    expect(advertsFromCapture(captured)).toEqual([
      { address: 'C0:FF:EE:00:00:01', name: 'Snapshot PB', rssi: -61, serviceData: [] },
      { address: 'C0:FF:EE:00:00:02', name: 'Something Else', rssi: -90, serviceData: [] }
    ]);
  });
});
