// Tests for the GATT notification router.

import { afterEach, beforeEach, describe, expect, test, vi } from 'vitest';
import { bleRecorder, installRadio } from '../mocks/zos-ble';
import { addNotificationRoute } from '../../src/bluetooth/notificationRouter';

const EYE_CHAR_UUID = '00000011-5350-4221-0000-5350425f4f54';
const OACP_SHORT_UUID = '2AC5';
const OACP_CANONICAL_UUID = '00002AC5-0000-1000-8000-00805F9B34FB';

describe('notification router', () => {
  let deliver;
  let removers;

  beforeEach(() => {
    deliver = null;
    removers = [];
    installRadio({
      attach(d) {
        deliver = d;
      },
      detach() {}
    });
  });

  afterEach(() => {
    removers.forEach(remove => remove());
    removers = [];
    installRadio(null);
    vi.restoreAllMocks();
  });

  function register(uuids, handler) {
    const remove = addNotificationRoute(uuids, handler);
    removers.push(remove);
    return remove;
  }

  test('registers only one mstOnCharaNotification callback', () => {
    expect(bleRecorder.listenerCounts().notification).toBe(1);
  });

  test('an eye notification reaches only the eye route', () => {
    const eyeEvents = [];
    const oacpEvents = [];
    register([EYE_CHAR_UUID], res => eyeEvents.push(res));
    register([OACP_SHORT_UUID], res => oacpEvents.push(res));

    const event = { characteristicUuid: EYE_CHAR_UUID, value: new Uint8Array([1]).buffer };
    deliver.deliverNotification(event);

    expect(eyeEvents).toEqual([event]);
    expect(oacpEvents).toEqual([]);
  });

  test('a canonical 128-bit 2AC5 notification reaches only the 2AC5 route', () => {
    const eyeEvents = [];
    const oacpEvents = [];
    register([EYE_CHAR_UUID], res => eyeEvents.push(res));
    register([OACP_SHORT_UUID], res => oacpEvents.push(res));

    const event = { characteristicUuid: OACP_CANONICAL_UUID, value: new Uint8Array([2]).buffer };
    deliver.deliverNotification(event);

    expect(oacpEvents).toEqual([event]);
    expect(eyeEvents).toEqual([]);
  });

  test('the remove function stops notifications to that route', () => {
    const received = [];
    const other = [];
    const remove = register([EYE_CHAR_UUID], res => received.push(res));
    register([OACP_SHORT_UUID], res => other.push(res));

    const first = { characteristicUuid: EYE_CHAR_UUID, value: new Uint8Array([1]).buffer };
    deliver.deliverNotification(first);
    expect(received).toEqual([first]);

    // A second remove call must do nothing and must not affect other routes.
    remove();
    remove();

    deliver.deliverNotification({ characteristicUuid: EYE_CHAR_UUID, value: new Uint8Array([2]).buffer });
    const oacpEvent = { characteristicUuid: OACP_SHORT_UUID, value: new Uint8Array([3]).buffer };
    deliver.deliverNotification(oacpEvent);

    expect(received).toEqual([first]);
    expect(other).toEqual([oacpEvent]);
  });

  test('a handler that throws does not stop a later notification', () => {
    const logSpy = vi.spyOn(console, 'log').mockImplementation(() => {});
    const received = [];
    let calls = 0;

    register([EYE_CHAR_UUID], (res) => {
      calls++;
      if (calls === 1) throw new Error('boom');
      received.push(res);
    });

    const first = { characteristicUuid: EYE_CHAR_UUID, value: new Uint8Array([1]).buffer };
    const second = { characteristicUuid: EYE_CHAR_UUID, value: new Uint8Array([2]).buffer };

    expect(() => deliver.deliverNotification(first)).not.toThrow();
    deliver.deliverNotification(second);

    expect(logSpy).toHaveBeenCalledTimes(1);
    expect(received).toEqual([second]);
  });

  test('an event with no characteristic UUID reaches no route', () => {
    const received = [];
    register([EYE_CHAR_UUID, OACP_SHORT_UUID], res => received.push(res));

    deliver.deliverNotification(null);
    deliver.deliverNotification({});
    deliver.deliverNotification({ characteristicUuid: '' });

    expect(received).toEqual([]);
  });

  test('an unmatched notification reaches no route and writes no log', () => {
    const logSpy = vi.spyOn(console, 'log').mockImplementation(() => {});
    const received = [];
    register([EYE_CHAR_UUID], res => received.push(res));

    deliver.deliverNotification({ characteristicUuid: '2AC6', value: new Uint8Array([4]).buffer });

    expect(received).toEqual([]);
    expect(logSpy).not.toHaveBeenCalled();
  });
});
