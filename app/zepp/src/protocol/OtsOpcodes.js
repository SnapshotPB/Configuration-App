// Object Transfer Service control-point opcodes and response codes.
// Mirrors the KMP source of truth in
// core/bluetooth/.../ots/OtsOpcodes.kt so both clients drive the same
// firmware identically. Do not invent values here — keep in sync with Kotlin.

// Object Action Control Point opcodes (OTS spec §3.11.2).
export const OACP = {
  CREATE: 0x01,
  DELETE: 0x02,
  CALCULATE_CHECKSUM: 0x03,
  EXECUTE: 0x04,
  READ: 0x05,
  WRITE: 0x06,
  ABORT: 0x07,
  RESPONSE_CODE: 0x60
};

// Object List Control Point opcodes (OTS spec §3.12.2).
export const OLCP = {
  FIRST: 0x01,
  LAST: 0x02,
  PREVIOUS: 0x03,
  NEXT: 0x04,
  GOTO: 0x05,
  ORDER: 0x06,
  REQUEST_NUMBER_OF_OBJECTS: 0x07,
  CLEAR_MARKING: 0x08,
  RESPONSE_CODE: 0x70
};

// Result code shared by both control points: 0x01 == Success
// (OTS spec §3.11.3 / §3.12.3).
export const RESULT_SUCCESS = 0x01;
