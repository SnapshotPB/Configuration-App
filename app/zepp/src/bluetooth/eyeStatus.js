// GATT UUIDs and protobuf decoder for the live eye status characteristic.

import { toDomainEyeReading } from '../protocol/OtsModelMapper';
import { snapshotpb } from '../protocol/proto';

const { EyeStatus } = snapshotpb.v1;

// Refer to the "Live eye status (GATT)" section of the protocol README.
// This characteristic supports read and notify.
// The board sends notifications at approximately 10 Hz only after the client
// writes the CCC in this connection. The CCC returns to 0 at each disconnect.
export const EyeStatusUuids = {
  Service: '00000010-5350-4221-0000-5350425f4f54',
  Status: '00000011-5350-4221-0000-5350425f4f54'
};

function toBytes(value) {
  if (ArrayBuffer.isView(value)) {
    return new Uint8Array(value.buffer, value.byteOffset, value.byteLength);
  }
  if (value instanceof ArrayBuffer || Array.isArray(value)) {
    return new Uint8Array(value);
  }
  throw new TypeError('EyeStatus value must be an ArrayBuffer, a typed array, or an array');
}

// Decodes a bare EyeStatus notification payload into an EyeReading object.
// Do not call trimProtobufPadding here. A notification has no flash padding.
// A message such as { level_raw: 0 } is the bytes 08 00, so a trim removes a
// real level. The caller catches any decode error.
export function decodeEyeStatus(value) {
  const bytes = toBytes(value);
  const decoded = EyeStatus.decode(bytes);
  return toDomainEyeReading(decoded);
}
