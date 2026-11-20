// Bluetooth at boot UI tests: board setting labels, saveOutcome messages,
// and card visibility on the device_config page.

import { afterEach, describe, expect, test } from 'vitest';
import { startHarness } from '../harness';
import { PAGES, syncNewBoard } from '../harness/flows';
import { BLUETOOTH_AT_BOOT, createBoardState } from '../virtual-board/boardState';
import { boardSettingLabel } from '../../src/utils/boardSettings';
import { SAVE_COLOR_OK, SAVE_COLOR_WARN, saveOutcome } from '../../src/utils/saveOutcome';

describe('bluetooth at boot UI', () => {
  let h;

  afterEach(() => {
    if (h) h.teardown();
    h = null;
  });

  test('formats Bluetooth at boot labels and saveOutcome refusal messages', () => {
    expect(boardSettingLabel('bluetoothAtBoot', 'OFF')).toBe('Off at Start');
    expect(boardSettingLabel('bluetoothAtBoot', 'RESTORE')).toBe('Remember State');

    const requested = { bluetoothAtBoot: 'RESTORE', knownProfiles: [] };
    const keptOff = { bluetoothAtBoot: 'OFF', knownProfiles: [] };
    const writtenOff = { bluetoothAtBoot: 'OFF' };
    expect(saveOutcome(requested, keptOff, writtenOff)).toEqual({
      text: 'Board kept Off at Start',
      color: SAVE_COLOR_WARN
    });

    const keptRestore = { bluetoothAtBoot: 'RESTORE', knownProfiles: [] };
    const writtenRestore = { bluetoothAtBoot: 'RESTORE' };
    expect(saveOutcome(requested, keptRestore, writtenRestore)).toEqual({
      text: 'Save Successful!',
      color: SAVE_COLOR_OK
    });
  });

  test('shows the BT AT BOOT card when the board reports a value and updates on selection', async () => {
    h = await startHarness();
    await syncNewBoard(h);

    expect(h.stack[h.stack.length - 1].__url).toBe(PAGES.deviceConfig);
    expect(h.hasText('BT AT BOOT')).toBe(true);
    expect(h.hasText('Off at Start')).toBe(true);

    h.click('Off at Start');
    expect(h.hasText('Bluetooth at Boot')).toBe(true);
    expect(h.hasText('Remember State')).toBe(true);

    h.click('Remember State');
    expect(h.stack[h.stack.length - 1].__url).toBe(PAGES.deviceConfig);
    expect(h.hasText('Remember State')).toBe(true);
    expect(h.store.getKnownDevices()[0].bluetoothAtBoot).toBe('RESTORE');
  });

  test('hides the BT AT BOOT card when the board reports no setting', async () => {
    h = await startHarness({
      state: createBoardState({
        deviceConfig: { bluetoothAtBoot: BLUETOOTH_AT_BOOT.BLUETOOTH_AT_BOOT_UNSPECIFIED }
      })
    });
    await syncNewBoard(h);

    expect(h.stack[h.stack.length - 1].__url).toBe(PAGES.deviceConfig);
    expect(h.hasText('BT AT BOOT')).toBe(false);
    expect(h.hasText('Off at Start')).toBe(false);
    expect(h.hasText('Remember State')).toBe(false);
  });
});
