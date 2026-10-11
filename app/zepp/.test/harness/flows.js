// Everything the tests know about THIS app — page urls, button labels, screen
// copy, the shape of a stored device — lives here and nowhere else.
//
// The harness and the virtual board are protocol-level and stable. The pages and
// their copy are not: when a label or a page name changes, this file is the only
// thing that should need editing.

export const PAGES = {
  deviceList: 'page/device_list/index',
  connecting: 'page/connecting/index',
  deviceConfig: 'page/device_config/index',
  profileSelection: 'page/profile_selection/index',
  profileEdit: 'page/profile_edit/index',
  profileField: 'page/profile_field/index',
  boardSettingSelection: 'page/board_setting_selection/index'
};

/** Substrings the tests tap or wait on. Substrings, not exact copy, on purpose. */
export const LABELS = {
  scan: 'Scan for Boards',
  stopScan: 'Stop Scanning',
  noSynced: 'No Synced Devices',
  // A discovered board renders as "<name> • <type> [#<last 4 of address>]".
  discovered: '[#',
  syncBoard: 'Sync Board',
  ignore: 'Ignore',
  useBoard: 'Use Board Values',
  keepMine: 'Keep My Values',
  cancel: 'Cancel',
  proceed: 'Continue',
  activeProfile: 'ACTIVE PROFILE',
  saveToBoard: 'Save to Board',
  // The line that takes the place of Save while the board reports lockout.
  lockout: 'Board in lockout',
  tuneProfile: 'Tune Profile',
  bleDisconnected: 'BLE Disconnected',
  // The device list when the watch cannot scan.
  bluetoothOff: 'Bluetooth is off',
  noPermission: 'Bluetooth permission',
  permissionInSettings: 'in the watch settings',
  allowBluetooth: 'Allow Bluetooth',
  tryAgain: 'Try Again'
};

/** The connecting page's terminal states, by the copy each one renders. */
export const SYNC_PROMPTS = {
  NewDevice: 'New Device Detected!',
  Conflict: 'Data Divergence Conflict!',
  Synced: 'All data loaded successfully.',
  Failed: 'Connection Failed'
};

/** Step glyphs from the connecting page's 4-step progress list. */
export const STEP = {
  pending: label => `o  ${label}`,
  inProgress: label => `>  ${label}`,
  completed: label => `v  ${label}`,
  failed: label => `x  ${label}`
};

/** Starts a scan, opening the device list first unless it is already on top. */
export async function startScan(h) {
  const top = h.stack[h.stack.length - 1];
  if (!top || top.__url !== PAGES.deviceList) h.open(PAGES.deviceList);
  h.click(LABELS.scan);
  await h.waitForText(LABELS.discovered);
}

/** Scans, taps the first discovered board, and lands on the connecting page. */
export async function tapDiscoveredBoard(h) {
  await startScan(h);
  h.click(LABELS.discovered);
}

/**
 * Waits for the connecting page to reach a terminal sync state.
 * @returns {'NewDevice'|'Conflict'|'Synced'|'Failed'}
 */
export async function awaitSyncPrompt(h) {
  const states = Object.keys(SYNC_PROMPTS);
  await h.waitFor(() => states.some(state => h.hasText(SYNC_PROMPTS[state])), {
    message: `connecting page never reached a sync state. On screen: ${JSON.stringify(h.texts())}`
  });
  return states.find(state => h.hasText(SYNC_PROMPTS[state]));
}

/** Scan -> connect -> whichever sync prompt the store's contents produce. */
export async function connectThroughPages(h) {
  await tapDiscoveredBoard(h);
  return awaitSyncPrompt(h);
}

/** The full new-board path, ending on the device configuration page. */
export async function syncNewBoard(h) {
  const prompt = await connectThroughPages(h);
  if (prompt !== 'NewDevice') {
    throw new Error(`expected a NewDevice prompt, got ${prompt}`);
  }
  h.click(LABELS.syncBoard);
  return h.store.getKnownDevices()[0];
}

/**
 * Registers the board in the store under `name`, the way the New Device prompt
 * does — which also records the board baseline (`boardState`) that every later
 * read is classified against.
 */
export async function registerKnownDevice(h, name = 'My Board') {
  const { device } = await h.connect();
  h.store.confirmNewDevice(device, name);
  return h.store.getKnownDevices()[0];
}

/**
 * Registers the board, then changes THE BOARD via `mutate`, so the next read
 * diverges from the stored baseline. The watch has no local edits, so the read
 * takes the board values with no prompt and reconciles as Synced.
 *
 * For a Conflict, the watch must also hold a local edit — see seedConflict.
 */
export async function seedBoardDivergence(h, mutate, name = 'My Board') {
  const stored = await registerKnownDevice(h, name);
  mutate(h.board.state);
  return stored;
}

// The local edit seedConflict makes when the test gives none. It changes a
// profile name, so the boot text stays free for the test to examine.
function editFirstProfileName(local) {
  local.knownProfiles[0].name = 'LOCALEDIT';
}

/**
 * Registers the board, edits the LOCAL copy via `mutateLocal`, then changes THE
 * BOARD via `mutateBoard`. The board moved out from under the watch while the
 * watch held an unsaved edit, so the next read reconciles as a Conflict.
 */
export async function seedConflict(h, mutateBoard, mutateLocal = editFirstProfileName, name = 'My Board') {
  const local = await seedLocalEdit(h, mutateLocal, name);
  mutateBoard(h.board.state);
  return local;
}

/**
 * Registers the board, then edits the LOCAL copy. The board still matches the
 * baseline, so the next read stays Synced and the edit shows as unsaved
 * (deviceStore.isDiverged) rather than raising a conflict on every reconnect.
 */
export async function seedLocalEdit(h, mutate, name = 'My Board') {
  const stored = await registerKnownDevice(h, name);
  const local = JSON.parse(JSON.stringify(stored));
  mutate(local);
  h.store.update([local]);
  return local;
}
