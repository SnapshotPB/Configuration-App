// Bluetooth at boot data-layer tests: mapper functions, unknown wire values,
// and reconnect sync rules.

import { afterEach, describe, expect, test } from 'vitest';
import { startHarness } from '../harness';
import { connectThroughPages, seedBoardDivergence, seedConflict } from '../harness/flows';
import { BLUETOOTH_AT_BOOT } from '../virtual-board/boardState';
import {
  BoardBluetoothAtBoot,
  bluetoothAtBootToDomain,
  bluetoothAtBootToProto
} from '../../src/protocol/OtsModelMapper';

describe('bluetooth at boot', () => {
  let h;

  afterEach(() => {
    if (h) h.teardown();
    h = null;
  });

  test('maps BluetoothAtBoot in both directions', () => {
    expect(bluetoothAtBootToDomain(0)).toBeNull();
    expect(bluetoothAtBootToDomain(1)).toBe(BoardBluetoothAtBoot.OFF);
    expect(bluetoothAtBootToDomain(2)).toBe(BoardBluetoothAtBoot.RESTORE);
    expect(bluetoothAtBootToDomain(7)).toBeNull();

    expect(bluetoothAtBootToProto(BoardBluetoothAtBoot.OFF)).toBe(1);
    expect(bluetoothAtBootToProto(BoardBluetoothAtBoot.RESTORE)).toBe(2);
    expect(bluetoothAtBootToProto(null)).toBe(0);
    expect(bluetoothAtBootToProto(undefined)).toBe(0);
    expect(bluetoothAtBootToProto('UNKNOWN')).toBe(0);
  });

  test('unknown bluetooth_at_boot value decodes as null and stays untouched on write', async () => {
    h = await startHarness();
    h.board.state.deviceConfig.bluetoothAtBoot = 7;

    const { device } = await h.connect();
    expect(device.bluetoothAtBoot).toBeNull();
    expect(device.bootText).toBe('SNAPSHOT');
    expect(device.boltMode).toBe('CLOSED');

    device.bootText = 'KEPT7';
    await h.writeConfiguration(h.address, device);

    expect(h.board.state.deviceConfig.bootText).toBe('KEPT7');
    expect(h.board.state.deviceConfig.bluetoothAtBoot).toBe(7);
  });

  test('a board bluetooth_at_boot change with no local edit takes the board value', async () => {
    h = await startHarness();
    const stored = await seedBoardDivergence(h, state => {
      state.deviceConfig.bluetoothAtBoot = BLUETOOTH_AT_BOOT.BLUETOOTH_AT_BOOT_RESTORE;
    });

    expect(await connectThroughPages(h)).toBe('Synced');

    const synced = h.store.getKnownDevices()[0];
    expect(synced.bluetoothAtBoot).toBe(BoardBluetoothAtBoot.RESTORE);
    expect(synced.boardState.bluetoothAtBoot).toBe(BoardBluetoothAtBoot.RESTORE);
    expect(h.store.isDiverged(stored.id)).toBe(false);
    expect(h.board.appliedWrites).toHaveLength(0);
  });

  test('a board bluetooth_at_boot change over a local edit raises a conflict', async () => {
    h = await startHarness();
    await seedConflict(h, state => {
      state.deviceConfig.bluetoothAtBoot = BLUETOOTH_AT_BOOT.BLUETOOTH_AT_BOOT_RESTORE;
    });

    expect(await connectThroughPages(h)).toBe('Conflict');
    expect(h.store.getKnownDevices()[0].bluetoothAtBoot).toBe(BoardBluetoothAtBoot.OFF);
  });
});
