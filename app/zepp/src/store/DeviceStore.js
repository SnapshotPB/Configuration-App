import { LocalStorage } from '@zos/storage';
import {
  EyeMode,
  FireMode,
  PROFILE_FIELD_NAMES,
  clampProfileToFirmwareRanges,
  normalizeDeviceForStorage,
} from '../protocol/OtsModelMapper';

const STORAGE_KEY = 'snapshot_pb_devices';
// The raw text of a store that did not parse. It is written once and not overwritten, so the
// first corrupt copy stays available for recovery.
const CORRUPT_BACKUP_KEY = 'snapshot_pb_devices_corrupt';
const localStorage = new LocalStorage();

// A deferred flash write waits this long after the last change. A stepper tap changes the store
// many times a second, and each full write goes to flash.
const DEFERRED_SAVE_MS = 1500;

// The snapshot of what the board holds, taken from a board read or from a written device. It
// mirrors the KMP BoardState: the writable fields plus the read-only lockout flag.
function boardStateOf(device) {
  return {
    bootText: device.bootText,
    activeProfileId: device.activeProfileId || null,
    lockoutEnabled: !!device.lockoutEnabled,
    knownProfiles: device.knownProfiles || [],
    language: device.language || null,
    screenBrightness: device.screenBrightness || null,
    screenOffDelay: device.screenOffDelay || null
  };
}

// A missing value matches any value. A device stored before the watch read a board setting has
// none, so the first read after an app update adopts the board's value instead of raising a
// conflict. A board that reports none (older firmware, or a value this app does not know) can
// never agree with a write, so it must not raise a conflict either. Mirrors the KMP
// boardValuesMatch().
function boardValuesMatch(stored, reported) {
  return stored == null || reported == null || stored === reported;
}

function profileEqual(x, y) {
  const a = x || {};
  const b = y || {};
  for (let j = 0; j < PROFILE_FIELD_NAMES.length; j++) {
    const f = PROFILE_FIELD_NAMES[j];
    const va = (a[f] === undefined || a[f] === null) ? null : a[f];
    const vb = (b[f] === undefined || b[f] === null) ? null : b[f];
    if (va !== vb) return false;
  }
  return true;
}

function profilesEqual(a, b) {
  const pa = a || [];
  const pb = b || [];
  if (pa.length !== pb.length) return false;
  for (let i = 0; i < pa.length; i++) {
    if (!profileEqual(pa[i], pb[i])) return false;
  }
  return true;
}

// Equality across the writable fields of [a] and [b]. Either one can be a device or a board
// snapshot. Local-only fields (name, photoPath, lastSeen, mac) are left out, so a rename or a
// reconnect does not raise a conflict. lockoutEnabled is a read-only board fact that no write can
// change, so it is adopted, not diffed. In the diff it made every "Keep My Values" resolution loop
// on the next connect.
function configEqual(a, b) {
  return a.bootText === b.bootText &&
    (a.activeProfileId || null) === (b.activeProfileId || null) &&
    boardValuesMatch(a.language, b.language) &&
    boardValuesMatch(a.screenBrightness, b.screenBrightness) &&
    boardValuesMatch(a.screenOffDelay, b.screenOffDelay) &&
    profilesEqual(a.knownProfiles, b.knownProfiles);
}

// The value the baseline records for a board setting after a write. The board reported [written],
// or it reported none and kept its own, in which case the old baseline stands. Mirrors the KMP
// settledBoardValue().
function settledBoardValue(written, baseline) {
  return written || baseline || null;
}

// The mode fields and the placeholder that an older build stored for an unknown mode.
const MODE_PLACEHOLDERS = [
  { field: 'fireMode', placeholder: FireMode.MECHANICAL, unknown: FireMode.UNKNOWN },
  { field: 'eyeMode', placeholder: EyeMode.OFF, unknown: EyeMode.UNKNOWN }
];

// [device] with each stored placeholder mode moved to UNKNOWN where [remote], a new board read,
// reports UNKNOWN. An older build read an unknown mode, or 0 (UNSPECIFIED), as MECHANICAL or OFF,
// and stored that placeholder in the profile and in the baseline. Without this step the first read
// after the update raises a conflict, and "Keep My Values" writes the placeholder over the mode of
// the board. The editable profile moves only when it still holds the baseline value, so a mode
// that the user selected stays. A device without a baseline does not change: without a baseline
// this function cannot know if the user selected the mode, so such a device can still raise a
// conflict for it. Returns [device] itself when nothing changes. Mirrors
// withPlaceholderModesAsUnknown in core:model.
function withPlaceholderModesAsUnknown(device, remote) {
  const baseline = device.boardState;
  if (!baseline) return device;
  const boardById = {};
  (remote.knownProfiles || []).forEach(p => { boardById[p.id] = p; });
  const baseById = {};
  (baseline.knownProfiles || []).forEach(p => { baseById[p.id] = p; });
  let changed = false;
  const migrate = (profile, base) => {
    const board = boardById[profile.id];
    if (!base || !board) return profile;
    let out = profile;
    MODE_PLACEHOLDERS.forEach(({ field, placeholder, unknown }) => {
      if (board[field] === unknown && base[field] === placeholder && out[field] === base[field]) {
        out = { ...out, [field]: unknown };
        changed = true;
      }
    });
    return out;
  };
  const knownProfiles = (device.knownProfiles || []).map(p => migrate(p, baseById[p.id]));
  const baseProfiles = (baseline.knownProfiles || []).map(p => migrate(p, p));
  if (!changed) return device;
  return { ...device, knownProfiles, boardState: { ...baseline, knownProfiles: baseProfiles } };
}

// [profile] with each UNKNOWN mode replaced by the mode of [board], the board profile at the same
// slot. A write sends the board's own number for an UNKNOWN mode, so after the write the board
// holds the mode of [board]. The stored copy must record it, or the next read raises the same
// conflict again.
function withUnknownModesFrom(profile, board) {
  if (!board) return profile;
  let out = profile;
  MODE_PLACEHOLDERS.forEach(({ field, unknown }) => {
    if (out[field] === unknown && board[field] != null) out = { ...out, [field]: board[field] };
  });
  return out;
}

export class DeviceStore {
  constructor() {
    this._persistTimer = null;
    this.devices = this._load();
  }

  // Keeps the raw text of a corrupt store under CORRUPT_BACKUP_KEY. The next save replaces the
  // main key, so without the backup the user's devices would be lost with no trace.
  _backUpCorrupt(raw, reason) {
    console.log(`Stored device list is corrupt (${reason}); it loads as empty.`);
    try {
      const existing = localStorage.getItem(CORRUPT_BACKUP_KEY);
      if (existing) {
        console.log(`A corrupt-store backup already exists under ${CORRUPT_BACKUP_KEY}; it is kept.`);
        return;
      }
      localStorage.setItem(CORRUPT_BACKUP_KEY, raw);
      console.log(`The corrupt store text is kept under ${CORRUPT_BACKUP_KEY}.`);
    } catch (e) {
      console.log("Backup of the corrupt store failed:", e);
    }
  }

  _load() {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return [];
    try {
      const parsed = JSON.parse(raw);
      if (!Array.isArray(parsed)) {
        this._backUpCorrupt(raw, 'not a list');
        return [];
      }
      // Migrate the legacy 0-1000 eye-threshold scale on load, and retire the legacy
      // whole-balls-per-second fire-rate cap, exactly as the Kotlin DeviceStore does. Without the
      // first a device stored before firmware a4b1c99 keeps an out-of-range value that the wire
      // clamp bounds but the local copy retains, so every reconnect raises a conflict the user
      // cannot resolve. Without the second a stored cap reads ten times too low.
      //
      // A device stored before the board baseline existed has no boardState. It loads as is: the
      // next matched read initializes the baseline (see onDeviceRead).
      return parsed.map(normalizeDeviceForStorage);
    } catch (e) {
      this._backUpCorrupt(raw, e && e.message ? e.message : 'parse error');
      return [];
    }
  }

  // Updates the in-memory list and writes it to flash. With options.defer the flash write waits
  // until no change came for DEFERRED_SAVE_MS; readers see the new list at once either way. A
  // write that is not deferred also writes any deferred change.
  _save(devices, options = {}) {
    this.devices = devices;
    if (options.defer) {
      this._clearPersistTimer();
      this._persistTimer = setTimeout(() => {
        this._persistTimer = null;
        this._persist();
      }, DEFERRED_SAVE_MS);
      return;
    }
    this._clearPersistTimer();
    this._persist();
  }

  _persist() {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(this.devices));
  }

  _clearPersistTimer() {
    if (this._persistTimer) {
      clearTimeout(this._persistTimer);
      this._persistTimer = null;
    }
  }

  // Writes a deferred change to flash now. Call it when the page that made deferred changes
  // closes, so no change waits on a timer that the runtime can stop.
  flush() {
    if (!this._persistTimer) return;
    this._clearPersistTimer();
    this._persist();
  }

  _saveDevice(deviceId, transform, options) {
    this._save(this.devices.map(d => (d.id === deviceId ? transform(d) : d)), options);
  }

  getKnownDevices() {
    return this.devices;
  }

  update(devices) {
    this._save(devices);
  }

  // Classifies a board read as NewDevice, Conflict or Synced, and stores what a Synced read
  // carries. Mirrors DeviceSyncDelegate.onDeviceRead on the Kotlin side.
  //
  // A device with a board baseline compares the board against that baseline, not against the
  // local copy. When they match, the board holds what the watch last saw, so a local edit that was
  // not saved is kept and shown as unsaved until Save or Discard. Without this rule an unsaved
  // edit raised a conflict on every reconnect, and the discard action never got a chance to run.
  // When they do not match, the board changed after the last read: a device with no local edits
  // takes the board values with no prompt, and a device with local edits raises a Conflict.
  //
  // A device without a baseline (stored before the baseline existed) compares the board against
  // the local copy, and a match initializes the baseline.
  onDeviceRead(remoteDevice) {
    const stored = this.devices.find(d => d.id === remoteDevice.id);
    if (!stored) {
      return { type: 'NewDevice', device: remoteDevice };
    }
    // An older build stored MECHANICAL or OFF for a mode that the board reports as UNKNOWN now.
    // Move those placeholders to UNKNOWN first, so the read does not raise a conflict for them.
    const local = withPlaceholderModesAsUnknown(stored, remoteDevice);
    if (local !== stored) {
      this._saveDevice(remoteDevice.id, d => withPlaceholderModesAsUnknown(d, remoteDevice));
    }

    if (!local.boardState) {
      if (!configEqual(local, remoteDevice)) {
        return { type: 'Conflict', local, remote: remoteDevice };
      }
      this._saveDevice(remoteDevice.id, d => ({
        ...d,
        lastSeen: remoteDevice.lastSeen,
        bootText: remoteDevice.bootText,
        // Equal, or one side had none (see boardValuesMatch): adopt the board's.
        language: remoteDevice.language,
        screenBrightness: remoteDevice.screenBrightness,
        screenOffDelay: remoteDevice.screenOffDelay,
        activeProfileId: remoteDevice.activeProfileId,
        lockoutEnabled: remoteDevice.lockoutEnabled,
        knownProfiles: remoteDevice.knownProfiles,
        // A known board can come back at a new BLE address; the next connect needs it.
        mac: remoteDevice.mac || d.mac,
        boardState: boardStateOf(remoteDevice)
      }));
      return { type: 'Synced', device: local };
    }

    if (!configEqual(local.boardState, remoteDevice)) {
      // The board changed after the last read. With no local edits the watch has nothing to lose,
      // so it takes the board values with no prompt, the same as "Use Board Values". Otherwise a
      // change in the board menu raised a conflict, and "Keep My Values" wrote the old value back.
      if (!this.isDiverged(local.id)) {
        console.log('The board changed and the watch has no local edits; it takes the board values.');
        this.resolveConflict(remoteDevice.id, 'TakeDevice', remoteDevice);
        return { type: 'Synced', device: local };
      }
      return { type: 'Conflict', local, remote: remoteDevice };
    }
    this._saveDevice(remoteDevice.id, d => ({
      ...d,
      lastSeen: remoteDevice.lastSeen,
      // The board owns lockoutEnabled, so it always tracks the latest read.
      lockoutEnabled: remoteDevice.lockoutEnabled,
      mac: remoteDevice.mac || d.mac,
      // A baseline with no value takes the board's, and a pending local choice is kept. A board
      // that reports none clears both, because the app has no value that it can show or write.
      language: remoteDevice.language == null ? null : (d.language || remoteDevice.language),
      screenBrightness: remoteDevice.screenBrightness == null
        ? null
        : (d.screenBrightness || remoteDevice.screenBrightness),
      screenOffDelay: remoteDevice.screenOffDelay == null
        ? null
        : (d.screenOffDelay || remoteDevice.screenOffDelay),
      boardState: {
        ...d.boardState,
        lockoutEnabled: !!remoteDevice.lockoutEnabled,
        language: remoteDevice.language || null,
        screenBrightness: remoteDevice.screenBrightness || null,
        screenOffDelay: remoteDevice.screenOffDelay || null
      }
    }));
    return { type: 'Synced', device: local };
  }

  confirmNewDevice(pendingDevice, name) {
    const normalized = normalizeDeviceForStorage({
      ...pendingDevice,
      name: name || pendingDevice.name,
      photoPath: ""
    });
    // The baseline takes the normalized profiles, so the stored copy and the baseline agree.
    const toStore = { ...normalized, boardState: boardStateOf(normalized) };
    // Guard against a double-confirm producing two entries with the same id.
    if (this.devices.some(d => d.id === toStore.id)) {
      this._saveDevice(toStore.id, () => toStore);
      return;
    }
    this._save([...this.devices, toStore]);
  }

  resolveConflict(deviceId, choice, remoteDevice) {
    const local = this.devices.find(d => d.id === deviceId);
    if (!local) return;

    if (choice === 'TakeDevice') {
      const merged = {
        ...remoteDevice,
        name: local.name,
        photoPath: local.photoPath,
        boardState: boardStateOf(remoteDevice)
      };
      this._saveDevice(deviceId, () => merged);
    } else if (choice === 'TakeLocal') {
      // Clamp the LOCAL copy to the firmware ranges, not just the wire value.
      //
      // toProtocolConfig() already clamps what goes on the wire, so the board ends up holding the
      // bounded value. Storing the raw local value here left the two permanently unequal, so the
      // next reconnect diffed unequal and raised this same conflict again — an unresolvable loop.
      // Clamping once and storing the clamped copy is what makes "keep my values" terminate.
      // Mirrors DeviceSyncDelegateImpl.resolveConflict(TakeLocal) on the Kotlin side.
      //
      // A local device with no value for a board setting takes the board's, so the write sends the
      // board's current value again and the stored copy agrees with the board.
      //
      // The baseline records what the board holds NOW. The write result settles it when the board
      // accepts the write (see applyWriteResult), so a failed write leaves the edits unsaved.
      const mergedLocal = {
        ...local,
        // An UNKNOWN mode goes out as the board's own number (toProtocolConfig pairs the slots by
        // index), so the stored copy records the mode that the board holds.
        knownProfiles: (local.knownProfiles || []).map((p, index) => withUnknownModesFrom(
          clampProfileToFirmwareRanges(p),
          (remoteDevice.knownProfiles || [])[index]
        )),
        language: local.language || remoteDevice.language || null,
        screenBrightness: local.screenBrightness || remoteDevice.screenBrightness || null,
        screenOffDelay: local.screenOffDelay || remoteDevice.screenOffDelay || null,
        // The board owns lockoutEnabled, so the local copy adopts the board's value.
        lockoutEnabled: remoteDevice.lockoutEnabled,
        lastSeen: remoteDevice.lastSeen,
        boardState: boardStateOf(remoteDevice)
      };
      this._saveDevice(deviceId, () => mergedLocal);
    }
  }

  updateDeviceActiveProfile(deviceId, activeProfileId) {
    this._saveDevice(deviceId, d => ({ ...d, activeProfileId }));
  }

  // Stores one board setting the user chose: 'language', 'screenBrightness' or 'screenOffDelay'.
  // The board gets it on the next save.
  //
  // The invariant every reader depends on: a setting is either null, meaning that the board
  // reported none, or a real value. screenOffDelay is therefore null or 1 to 999, and NEVER 0 --
  // several readers use `||`, which would drop a 0. On the wire 0 means "leave the board's delay
  // alone", so a 0 here would be a value the app chose that the board would then ignore.
  updateBoardSetting(deviceId, field, value) {
    this._saveDevice(deviceId, d => ({ ...d, [field]: value }));
  }

  // Stores one field of one profile: a stepped number, the fire mode or the eye mode. The board
  // gets it on the next save. The change lands on top of whatever the store holds, so the other
  // fields keep their current values.
  //
  // With options.defer the flash write waits (see _save). The stepper page uses it, because each
  // tap is one change; it calls flush() when it closes.
  updateProfileField(deviceId, profileId, field, value, options) {
    this._saveDevice(deviceId, d => ({
      ...d,
      knownProfiles: (d.knownProfiles || []).map(p => (
        p.id === profileId ? { ...p, [field]: value } : p
      ))
    }), options);
  }

  // Whether the local copy differs from the board baseline in a field a save would write. A device
  // without a baseline has nothing to compare, so it is not diverged.
  isDiverged(deviceId) {
    const device = this.devices.find(d => d.id === deviceId);
    if (!device || !device.boardState) return false;
    return !configEqual(device, device.boardState);
  }

  // Whether one profile differs from its copy in the board baseline.
  isProfileDiverged(deviceId, profileId) {
    const device = this.devices.find(d => d.id === deviceId);
    if (!device || !device.boardState) return false;
    const local = (device.knownProfiles || []).find(p => p.id === profileId);
    const base = (device.boardState.knownProfiles || []).find(p => p.id === profileId);
    // One side lacks the profile: diverged when the other side holds it.
    if (!local || !base) return !!local !== !!base;
    return !profileEqual(local, base);
  }

  // Whether one field of one profile differs from the board baseline.
  isProfileFieldDiverged(deviceId, profileId, field) {
    const device = this.devices.find(d => d.id === deviceId);
    if (!device || !device.boardState) return false;
    const local = (device.knownProfiles || []).find(p => p.id === profileId);
    const base = (device.boardState.knownProfiles || []).find(p => p.id === profileId);
    if (!local || !base) return false;
    const vl = local[field] == null ? null : local[field];
    const vb = base[field] == null ? null : base[field];
    return vl !== vb;
  }

  // Restores every writable field from the board baseline. lockoutEnabled is a read-only board
  // fact, not an edit, so it stays. The board settings restore the baseline EXACTLY, with no
  // fallback to the value the device holds: a fallback would keep the very value the user asked to
  // discard. Mirrors DeviceViewModelDelegate.discardPendingChanges.
  discardPendingChanges(deviceId) {
    const device = this.devices.find(d => d.id === deviceId);
    if (!device || !device.boardState) return;
    const bs = device.boardState;
    this._saveDevice(deviceId, d => ({
      ...d,
      bootText: bs.bootText,
      activeProfileId: bs.activeProfileId,
      knownProfiles: bs.knownProfiles,
      language: bs.language,
      screenBrightness: bs.screenBrightness,
      screenOffDelay: bs.screenOffDelay
    }));
  }

  // Restores one profile from the board baseline and leaves the boot text, the board settings and
  // the other profiles alone. A profile the baseline does not hold stays as it is.
  discardProfileChanges(deviceId, profileId) {
    const device = this.devices.find(d => d.id === deviceId);
    if (!device || !device.boardState) return;
    const base = (device.boardState.knownProfiles || []).find(p => p.id === profileId);
    if (!base) return;
    this._saveDevice(deviceId, d => ({
      ...d,
      knownProfiles: (d.knownProfiles || []).map(p => (p.id === profileId ? base : p))
    }));
  }

  // Settles the store after the board accepted a write. Mirrors
  // DeviceViewModelDelegate.onWriteResult on the Kotlin side.
  //
  // [requested] is the device the page sent. [written] holds the board settings the board reported
  // after the write (see OtsClient.writeDeviceConfiguration): it ignores a language or a brightness
  // that it does not know, and it clamps the delay, so these can differ from the values that were
  // sent. [written] is null when the read-back failed. The write itself succeeded, so the baseline
  // then takes the values that were sent, as the KMP engine does: an old baseline value would
  // raise a conflict on the next connect against a board that holds the sent value.
  //
  // The baseline always tracks what the board now holds: the clamped profiles and the reported
  // settings. The editable copy adopts the same values only when nothing changed it since the
  // write was dispatched; an edit that landed during the write is kept, and the refreshed baseline
  // shows it as unsaved.
  applyWriteResult(deviceId, requested, written) {
    this._saveDevice(deviceId, d => {
      const base = d.boardState || {};
      const source = requested || d;
      const reported = written || {
        language: source.language,
        screenBrightness: source.screenBrightness,
        screenOffDelay: source.screenOffDelay
      };
      const settled = {
        language: settledBoardValue(reported.language, base.language),
        screenBrightness: settledBoardValue(reported.screenBrightness, base.screenBrightness),
        screenOffDelay: settledBoardValue(reported.screenOffDelay, base.screenOffDelay)
      };
      const baseline = {
        bootText: source.bootText,
        activeProfileId: source.activeProfileId || null,
        lockoutEnabled: !!d.lockoutEnabled,
        knownProfiles: (source.knownProfiles || []).map(clampProfileToFirmwareRanges),
        ...settled
      };
      const editedInFlight = !!requested && !(
        d.bootText === requested.bootText &&
        (d.activeProfileId || null) === (requested.activeProfileId || null) &&
        (d.language || null) === (requested.language || null) &&
        (d.screenBrightness || null) === (requested.screenBrightness || null) &&
        (d.screenOffDelay || null) === (requested.screenOffDelay || null) &&
        profilesEqual(d.knownProfiles, requested.knownProfiles)
      );
      if (editedInFlight) {
        return { ...d, boardState: baseline };
      }
      // The editable copy adopts the settings the board reported. When the read-back failed the
      // watch cannot tell what the board kept, so the chosen values stay and the page reports the
      // save as not verified.
      const adopted = written ? settled : {
        language: d.language || null,
        screenBrightness: d.screenBrightness || null,
        screenOffDelay: d.screenOffDelay || null
      };
      return {
        ...d,
        bootText: baseline.bootText,
        activeProfileId: baseline.activeProfileId,
        knownProfiles: baseline.knownProfiles,
        ...adopted,
        boardState: baseline
      };
    });
  }
}
export const deviceStore = new DeviceStore();
