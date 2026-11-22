// Helpers that match the UUID and the address of a GATT event.

export function uuidMatches(received, target) {
  const r = (received || '').toUpperCase();
  const t = (target || '').toUpperCase();
  // Reject empty/missing UUIDs — "ANY".indexOf("") === 0 would otherwise match
  // every characteristic when an event arrives without a UUID field.
  if (!r || !t) return false;
  // Substring (either direction) — a 16-bit assigned UUID like "2AC5" sits at
  // offset 4-7 inside its 128-bit canonical form
  // (0000XXXX-0000-1000-8000-00805F9B34FB), so endsWith would miss it when the
  // stack reports canonical UUIDs. The vendor 128-bit UUIDs still match via
  // exact equality, so substring matching introduces no false positives here.
  return r === t || r.indexOf(t) !== -1 || t.indexOf(r) !== -1;
}

export function eventAddress(res) {
  return (res && (res.address || res.dev_id || res.dev || res.deviceSource)) || null;
}

// Reject an event only when the addresses do not match. Many @zos/ble stacks do
// not add an address to a characteristic event. With one connection, those
// events still reach the correct client, so an event with no address must pass.
export function addressMatches(res, address) {
  if (!address) return true;
  const ra = eventAddress(res);
  if (!ra) return true;
  return String(ra).toUpperCase() === String(address).toUpperCase();
}

export function eventCharacteristicUuid(res) {
  return (res && (res.characteristicUuid || res.charaUuid)) || '';
}
