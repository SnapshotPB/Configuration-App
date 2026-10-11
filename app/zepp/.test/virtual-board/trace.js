// Trace capture and replay.
//
// VirtualRadio records every client request together with the responses it
// produced (`radio.script`). A ScriptedBoard replays such a script in place of
// a VirtualBoard, which is the seam for driving the suite from a capture taken
// off real hardware instead of from simulated firmware.
//
// Capturing from a real watch today: BleManager.js:99 already logs
// `JSON.stringify(res)` for every discovered device — feed those lines to
// advertsFromCapture() to replay a real scan. A full GATT capture needs the same
// treatment at the OtsClient event handlers; see .test/README.md.

import { normalizeUuid } from './VirtualBoard';

function bytesFromHex(hex) {
  const clean = String(hex || '').replace(/[^0-9a-fA-F]/g, '');
  const out = new Uint8Array(clean.length / 2);
  for (let i = 0; i < out.length; i++) out[i] = parseInt(clean.slice(i * 2, i * 2 + 2), 16);
  return out;
}

function hexFromBytes(bytes) {
  return Array.from(bytes, b => b.toString(16).padStart(2, '0')).join('');
}

/**
 * Turns captured `Discovered device over BLE: {...}` log lines (or the parsed
 * objects) into radio device entries, so a real scan can be replayed verbatim.
 */
export function advertsFromCapture(captured) {
  const entries = Array.isArray(captured) ? captured : [captured];
  return entries
    .map(entry => {
      if (typeof entry !== 'string') return entry;
      const start = entry.indexOf('{');
      if (start === -1) return null;
      try {
        return JSON.parse(entry.slice(start));
      } catch (_) {
        return null;
      }
    })
    .filter(Boolean)
    .map(res => ({
      address: res.address || res.dev_id,
      name: res.name,
      rssi: res.rssi,
      serviceData: res.service_data || []
    }));
}

/**
 * A board that answers from a recorded script rather than simulating firmware.
 * Implements the same surface VirtualRadio drives on VirtualBoard, so it drops
 * straight into a radio's device list.
 */
const GATT_REQUESTS = [
  'mstReadCharacteristic',
  'mstWriteCharacteristic',
  'mstPrepareNotificationChar',
  'mstPrepareIndicateChar'
];

export class ScriptedBoard {
  constructor(script) {
    // Only the GATT exchange replays — scan/connect/disconnect are the radio's
    // business and carry no board responses.
    this.script = script.filter(entry => GATT_REQUESTS.includes(entry.request.fn));
    this.cursor = 0;
    this.transport = null;
    this.divergence = null;
  }

  attach(transport) {
    this.transport = transport;
  }

  resetSession() {
    // Only the cursor is session state. A divergence is a diagnostic about the
    // script itself and must survive the disconnect that a failure triggers.
    this.cursor = 0;
  }

  readCharacteristic(uuid) {
    const entry = this._take('mstReadCharacteristic', uuid);
    if (!entry) return { error: this.divergence };
    const response = entry.responses.find(r => r.channel === 'value');
    if (!response) return { error: `recorded read of ${normalizeUuid(uuid)} produced no value` };
    this._emitRest(entry, response);
    return { value: bytesFromHex(response.hex) };
  }

  writeCharacteristic(uuid, bytes) {
    const entry = this._take('mstWriteCharacteristic', uuid, hexFromBytes(bytes));
    if (!entry) return { error: this.divergence };
    this._emitRest(entry, null);
    return { ok: true };
  }

  setSubscription(uuid, enable, kind) {
    // Subscribes carry no recorded response; consume the matching entry so the
    // cursor stays aligned with the recorded request order.
    this._take(kind === 'indicate' ? 'mstPrepareIndicateChar' : 'mstPrepareNotificationChar', uuid);
    return { ok: true };
  }

  _take(fn, uuid, requestHex) {
    const entry = this.script[this.cursor];
    const wanted = normalizeUuid(uuid);
    if (!entry) {
      this.divergence = `script exhausted: client asked for ${fn} ${wanted}`;
      return null;
    }
    if (entry.request.fn !== fn || normalizeUuid(entry.request.uuid) !== wanted) {
      this.divergence =
        `script diverged at ${this.cursor}: recorded ${entry.request.fn} ${normalizeUuid(entry.request.uuid)}, ` +
        `client sent ${fn} ${wanted}`;
      return null;
    }
    if (requestHex !== undefined && entry.request.hex !== undefined && entry.request.hex !== requestHex) {
      this.divergence =
        `script diverged at ${this.cursor}: recorded write ${entry.request.hex}, client sent ${requestHex}`;
      return null;
    }
    this.cursor += 1;
    return entry;
  }

  /** Replays every response of an entry except the one returned synchronously. */
  _emitRest(entry, consumed) {
    entry.responses.forEach((response, index) => {
      if (response === consumed) return;
      this.transport.schedule(() => {
        const bytes = bytesFromHex(response.hex);
        if (response.channel === 'notify') this.transport.sendNotification(response.uuid, bytes);
        else this.transport.sendValue(response.uuid, bytes);
      }, index);
    });
  }
}
