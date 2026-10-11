// Aligns Node's protobufjs with the Zepp OS JS runtime.
//
// protobufjs picks its byte representation at load time: on Node it finds the
// global Buffer and makes Writer.finish() return a Node Buffer, whose .slice()
// is an ALIAS into protobufjs's shared 8192-byte allocation pool rather than a
// copy. The watch has no Buffer, so it takes the Uint8Array path where .slice()
// copies and `chunk.buffer` is exactly the chunk.
//
// That difference is not cosmetic: OtsClient chunks a write with
// `bytes.slice(i, i + 20)` and hands the GATT layer `chunk.buffer`. Under Node's
// Buffer the app would transmit the whole 8KB pool; on the watch it transmits 20
// bytes. Forcing the Uint8Array path here makes the harness encode and slice
// exactly as the device does, so a write test proves the device's behaviour and
// not Node's.
//
// Import this before anything that encodes or decodes protobuf.
//
// The watch runtime also does not always supply TextDecoder or TextEncoder. Node
// supplies both, so app code that used them without a guard would pass here and
// throw a ReferenceError on the watch. Delete them, so that the harness matches
// the watch. Test code that needs them imports them from node:util.

import * as protobuf from 'protobufjs/minimal';

if (protobuf.util.Buffer) {
  protobuf.util.Buffer = null;
  protobuf.configure();
}

delete globalThis.TextDecoder;
delete globalThis.TextEncoder;

export const usesNodeBuffer = !!protobuf.util.Buffer;
