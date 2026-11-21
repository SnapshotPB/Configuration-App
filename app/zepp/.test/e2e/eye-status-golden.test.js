// Golden wire-format tests for EyeStatus encode and decode.

import '../mocks/runtime';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { describe, expect, test } from 'vitest';
import { decodeEyeStatus } from '../../src/bluetooth/eyeStatus';
import { EyeSensorState } from '../../src/protocol/OtsModelMapper';
import { snapshotpb } from '../../src/protocol/proto';

const { EyeStatus } = snapshotpb.v1;

const goldenPath = fileURLToPath(
  new URL(
    '../../../../core/protocol/builder/Configuration-Protocol/testdata/eye_status.json',
    import.meta.url
  )
);
const GOLDEN = JSON.parse(readFileSync(goldenPath, 'utf8'));

function bytesFromHex(hex) {
  const bytes = new Uint8Array(hex.length / 2);
  for (let i = 0; i < bytes.length; i++) {
    bytes[i] = parseInt(hex.slice(i * 2, i * 2 + 2), 16);
  }
  return bytes;
}

function hexFromBytes(bytes) {
  return Array.from(bytes, b => b.toString(16).padStart(2, '0')).join('');
}

function snakeToCamel(key) {
  return key.replace(/_([a-z])/g, (_, ch) => ch.toUpperCase());
}

function expectedState(name) {
  if (!name) return EyeSensorState.UNKNOWN;
  const short = name.replace(/^EYE_SENSOR_STATE_/, '');
  return short === 'UNSPECIFIED' ? EyeSensorState.UNKNOWN : short;
}

function fieldsToProtoObject(fields) {
  const obj = {};
  Object.keys(fields).forEach((key) => {
    const value = fields[key];
    // Proto3 omits default values of non-optional fields on the wire.
    // protobufjs encodes every own property on a message, so omit those defaults here.
    if (key === 'state' && (value === 'EYE_SENSOR_STATE_UNSPECIFIED' || value === 0)) return;
    if (key === 'full_scale_raw' && value === 0) return;
    obj[snakeToCamel(key)] = value;
  });
  return obj;
}

describe('EyeStatus golden tests', () => {
  test('the golden file names EyeStatus and has at least 7 cases', () => {
    expect(GOLDEN.message).toBe('snapshotpb.v1.EyeStatus');
    expect(GOLDEN.proto).toBe('snapshotpb/v1/eye_status.proto');
    expect(GOLDEN.cases.length).toBeGreaterThanOrEqual(7);
  });

  test('decodes each golden case', () => {
    GOLDEN.cases.forEach((c) => {
      const fields = c.fields;
      const expected = {
        levelRaw: fields.level_raw !== undefined ? fields.level_raw : null,
        fullScaleRaw: fields.full_scale_raw !== undefined ? fields.full_scale_raw : 0,
        state: expectedState(fields.state)
      };
      expect(decodeEyeStatus(bytesFromHex(c.hex)), c.name).toEqual(expected);
    });
  });

  test('encodes each golden case', () => {
    GOLDEN.cases.forEach((c) => {
      const message = EyeStatus.fromObject(fieldsToProtoObject(c.fields));
      const encoded = EyeStatus.encode(message).finish();
      expect(hexFromBytes(encoded), c.name).toBe(c.hex);
    });
  });

  test('decodes an unknown state value to UNKNOWN with a null level', () => {
    expect(decodeEyeStatus(bytesFromHex('10ff1f1809'))).toEqual({
      levelRaw: null,
      fullScaleRaw: 4095,
      state: EyeSensorState.UNKNOWN
    });
  });

  test('decodes a level of 0 alone to 0 and not null', () => {
    expect(decodeEyeStatus(bytesFromHex('0800'))).toEqual({
      levelRaw: 0,
      fullScaleRaw: 0,
      state: EyeSensorState.UNKNOWN
    });
  });

  test('throws on bytes that do not decode', () => {
    expect(() => decodeEyeStatus(bytesFromHex('ff'))).toThrow();
  });

  test('accepts an ArrayBuffer and a plain array', () => {
    const bytes = bytesFromHex('080010ff1f1801');
    const expected = {
      levelRaw: 0,
      fullScaleRaw: 4095,
      state: EyeSensorState.ACTIVE
    };
    expect(decodeEyeStatus(bytes.buffer)).toEqual(expected);
    expect(decodeEyeStatus(Array.from(bytes))).toEqual(expected);
  });
});
