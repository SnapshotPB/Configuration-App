// The test harness: installs the Zepp host globals and the @zos mock layer,
// wires a VirtualRadio + VirtualBoard behind ble.mst*, and runs the app's real
// pages against them.
//
// App modules are imported dynamically and cached, because app.js calls the
// global App({...}) and every page calls the global Page({...}) at import time —
// those globals must exist first, and an ES module body only ever runs once.
// Per-test isolation therefore comes from resetting the singletons, not from
// re-importing them.

import '../mocks/runtime';
import {
  installClock,
  installConsole,
  installGlobals,
  logs,
  realSetTimeout,
  resetGlobalData,
  resetLogs,
  restoreClock,
  takeLastPageDefinition,
  timeScale
} from '../mocks/globals';
import { uiRecorder } from '../mocks/zos-ui';
import { routerRecorder } from '../mocks/zos-router';
import { storageRecorder } from '../mocks/zos-storage';
import { deviceRecorder } from '../mocks/zos-device';
import { appRecorder } from '../mocks/zos-app';
import { installRadio } from '../mocks/zos-ble';
import { VirtualBoard } from '../virtual-board/VirtualBoard';
import { VirtualRadio } from '../virtual-board/VirtualRadio';
import { createBoardState } from '../virtual-board/boardState';

// Each harness gets its OWN board address.
//
// The app's singletons outlive a single test. BleManager closes its OtsClient on
// disconnect, and a closed client refuses new GATT ops and sends no unsubscribes.
// A GATT call that a stale client still makes goes through the module-level
// ble.mst* functions, which resolve to whatever radio is installed WHEN THEY
// RUN — i.e. the next test's.
// Distinct addresses make that impossible: the stale client's calls no longer
// resolve to a live link, exactly as if it were talking to a board that walked
// away.
let addressSeq = 0;

export function nextAddress() {
  addressSeq += 1;
  const hi = ((addressSeq >> 8) & 0xff).toString(16).padStart(2, '0').toUpperCase();
  const lo = (addressSeq & 0xff).toString(16).padStart(2, '0').toUpperCase();
  return `AA:BB:CC:DD:${hi}:${lo}`;
}

// Static thunks rather than a computed import specifier so the module graph
// stays analysable. app-manifest.test.js asserts this covers app.json.
export const PAGE_LOADERS = {
  'page/device_list/index': () => import('../../page/device_list/index.js'),
  'page/connecting/index': () => import('../../page/connecting/index.js'),
  'page/device_config/index': () => import('../../page/device_config/index.js'),
  'page/profile_selection/index': () => import('../../page/profile_selection/index.js'),
  'page/profile_edit/index': () => import('../../page/profile_edit/index.js'),
  'page/profile_field/index': () => import('../../page/profile_field/index.js'),
  'page/board_setting_selection/index': () => import('../../page/board_setting_selection/index.js')
};

// A function, not a constant: options.timeScale can change the installed clock
// after this module is evaluated.
function defaultTimeout() {
  return Math.round(10000 * (timeScale() / 0.05));
}

let appModules = null;

async function loadAppModules() {
  if (appModules) return appModules;
  installGlobals();

  await import('../../app.js');
  const { bleManager, BluetoothState, StepStatus } = await import('../../src/bluetooth/BleManager.js');
  const { deviceStore } = await import('../../src/store/DeviceStore.js');
  const syncCoordinator = await import('../../src/store/syncCoordinator.js');
  const mapper = await import('../../src/protocol/OtsModelMapper.js');

  const pages = {};
  for (const url of Object.keys(PAGE_LOADERS)) {
    await PAGE_LOADERS[url]();
    const definition = takeLastPageDefinition();
    if (!definition) throw new Error(`${url} did not register a Page({...}) definition`);
    pages[url] = definition;
  }

  appModules = { bleManager, BluetoothState, StepStatus, deviceStore, syncCoordinator, mapper, pages };
  return appModules;
}

// Lifecycle hooks the page host drives. Anything else a page declares would be
// silently skipped, so the host refuses to run it rather than testing a page
// that never receives half its lifecycle.
const DRIVEN_HOOKS = ['onInit', 'build', 'onResume', 'onDestroy'];
const PAGE_HOOK_PATTERN = /^on[A-Z]|^build$/;

function assertHooksDriven(url, definition) {
  const undriven = Object.keys(definition).filter(
    key =>
      typeof definition[key] === 'function' &&
      PAGE_HOOK_PATTERN.test(key) &&
      !DRIVEN_HOOKS.includes(key)
  );
  if (undriven.length) {
    throw new Error(
      `${url} declares lifecycle hook(s) the harness does not drive: ${undriven.join(', ')}. ` +
        'Teach the page host to call them (see DRIVEN_HOOKS) before relying on this page in a test.'
    );
  }
}

/** Polls on real wall-clock time, so the compressed app clock cannot stall it. */
export function waitFor(predicate, options = {}) {
  const timeout = options.timeout || defaultTimeout();
  const interval = options.interval || 5;
  return new Promise((resolve, reject) => {
    const start = Date.now();
    const tick = () => {
      let value;
      try {
        value = predicate();
      } catch (e) {
        reject(e);
        return;
      }
      if (value) {
        resolve(value);
        return;
      }
      if (Date.now() - start > timeout) {
        reject(new Error(options.message || `waitFor timed out after ${timeout}ms`));
        return;
      }
      realSetTimeout(tick, interval);
    };
    tick();
  });
}

/**
 * @param {object} [options]
 * @param {object} [options.state]          board state fixture
 * @param {object} [options.boardOptions]   VirtualBoard options (chunkSize, padAlignment, …)
 * @param {object} [options.faults]         fault injection, shared by radio and board
 * @param {Array}  [options.knownDevices]   devices to seed DeviceStore with
 * @param {Array}  [options.devices]        radio device list (defaults to one board)
 * @param {'canonical'|'short'} [options.uuidFormat]
 * @param {object} [options.board]           a prebuilt board (e.g. a ScriptedBoard)
 * @param {object} [options.latency]         { scan, connect, gatt, notify } in board-ms
 * @param {number} [options.timeScale]       overrides ZEPP_TEST_TIME_SCALE
 */
export async function startHarness(options = {}) {
  installConsole();
  resetLogs();
  const modules = await loadAppModules();

  // Reset the app's singletons. disconnect() is the public way to clear the
  // BleManager's link state and invalidate any in-flight connect generation.
  modules.bleManager.stopScanning();
  modules.bleManager.disconnect();
  // A refused permission request is remembered for the life of the app. A new
  // harness is a new app launch.
  modules.bleManager.permissionRequestRefused = false;
  modules.bleManager._permissionRequestPending = false;

  // disconnect() hands the outgoing OtsClient a FIRE-AND-FORGET close(). Any
  // callback chain of the old client resolves through the module-level ble.mst*
  // functions to whatever radio is installed when it runs. Draining a macrotask
  // here — while no radio is installed — lets that chain settle against nothing
  // instead of against the radio we are about to install. Distinct per-harness addresses make a stale
  // call harmless; this makes it structurally impossible.
  await new Promise(resolve => realSetTimeout(resolve, 0));

  uiRecorder.reset();
  routerRecorder.reset();
  storageRecorder.reset();
  deviceRecorder.reset();
  appRecorder.reset();
  resetGlobalData();
  modules.deviceStore.update(options.knownDevices ? JSON.parse(JSON.stringify(options.knownDevices)) : []);

  // Copied, so a fault fixture reused across harnesses is never mutated.
  const faults = { ...(options.faults || {}) };
  const devices = options.devices || [
    {
      address: nextAddress(),
      name: 'Snapshot PB',
      board:
        options.board ||
        new VirtualBoard({ state: options.state || createBoardState(), faults, ...(options.boardOptions || {}) })
    }
  ];
  // Always the board actually on the other end of the link — never a phantom
  // one that no byte ever reaches.
  const board = devices[0] && devices[0].board;
  const radio = new VirtualRadio({
    devices,
    faults,
    uuidFormat: options.uuidFormat,
    latency: options.latency
  });
  installRadio(radio);
  installClock(options.timeScale);

  const stack = [];
  let nextPageId = 1;

  const open = url => {
    const definition = modules.pages[url];
    if (!definition) throw new Error(`no page registered for "${url}"`);
    assertHooksDriven(url, definition);
    // A fresh copy per navigation: Zepp instantiates a page object per visit,
    // and the definition's own `this` state must not leak between them. This is
    // a SHALLOW copy — safe only because every page assigns its mutable state
    // (dynamicWidgets, flags) inside onInit. A page that declared state on the
    // definition literal would share it across navigations.
    const page = Object.assign({}, definition);
    page.__url = url;
    page.__id = `page-${nextPageId++}`;
    stack.push(page);
    uiRecorder.setCurrentPage(page.__id);
    if (typeof page.onInit === 'function') page.onInit();
    if (typeof page.build === 'function') page.build();
    // Zepp shows a page immediately after build; device_config draws its whole
    // body from onResume, so the harness must fire it on first display too.
    if (typeof page.onResume === 'function') page.onResume();
    return page;
  };

  const destroyTop = () => {
    const page = stack.pop();
    if (!page) return null;
    uiRecorder.setCurrentPage(page.__id);
    if (typeof page.onDestroy === 'function') page.onDestroy();
    uiRecorder.clearPage(page.__id);
    return page;
  };

  const resumeTop = () => {
    const page = stack[stack.length - 1];
    if (!page) return null;
    uiRecorder.setCurrentPage(page.__id);
    if (typeof page.onResume === 'function') page.onResume();
    return page;
  };

  routerRecorder.setHandler((type, routeOptions) => {
    if (type === 'push') {
      open(routeOptions.url);
    } else if (type === 'replace') {
      destroyTop();
      open(routeOptions.url);
    } else if (type === 'back') {
      destroyTop();
      resumeTop();
    } else if (type === 'home') {
      while (stack.length > 1) destroyTop();
      resumeTop();
    }
  });

  const harness = {
    board,
    radio,
    devices,
    faults,
    address: devices[0] && devices[0].address,
    ble: modules.bleManager,
    store: modules.deviceStore,
    sync: modules.syncCoordinator,
    mapper: modules.mapper,
    BluetoothState: modules.BluetoothState,
    StepStatus: modules.StepStatus,
    ui: uiRecorder,
    router: routerRecorder,
    app: appRecorder,
    stack,
    logs,

    open,
    back: () => {
      destroyTop();
      resumeTop();
    },

    /** Every text string currently rendered, in creation order. */
    texts: () => uiRecorder.texts(),
    hasText: substring => uiRecorder.hasText(substring),

    /** Taps the first live widget whose text contains `substring`. */
    click(substring) {
      const w = uiRecorder.findByText(substring);
      if (!w) {
        throw new Error(
          `no widget with text containing "${substring}". On screen: ${JSON.stringify(uiRecorder.texts())}`
        );
      }
      return harness.clickWidget(w);
    },

    clickWhere(predicate) {
      const w = uiRecorder.find(predicate);
      if (!w) {
        throw new Error(`no widget matched the predicate. On screen: ${JSON.stringify(uiRecorder.texts())}`);
      }
      return harness.clickWidget(w);
    },

    clickWidget(w) {
      if (typeof w.props.click_func !== 'function') {
        throw new Error(`widget "${w.props.text}" has no click_func`);
      }
      // Attribute anything the handler draws to the tapped widget's page, then
      // restore — otherwise widgets a dead page draws later get charged to (and
      // freed with) whichever page happens to be current.
      const previous = uiRecorder.currentPage();
      uiRecorder.setCurrentPage(w.page);
      try {
        w.props.click_func(w);
      } finally {
        uiRecorder.setCurrentPage(previous);
      }
      return w;
    },

    waitFor,
    waitForText: (substring, waitOptions) =>
      waitFor(() => uiRecorder.hasText(substring), {
        message: `timed out waiting for "${substring}". On screen: ${JSON.stringify(uiRecorder.texts())}`,
        ...waitOptions
      }),

    /** The slot UUID the mapper derives for a board profile slot. */
    profileUuid(slotIndex, deviceId) {
      return modules.mapper.getProfileUuid(deviceId || board.state.device.deviceId, slotIndex);
    },

    deviceUuid(deviceId) {
      return modules.mapper.bytesToUuidString(deviceId || board.state.device.deviceId);
    },

    /** Drives BleManager.connect() directly, without the page layer. */
    connect(device) {
      const target = device || { id: harness.address, name: 'Snapshot PB' };
      const progress = [];
      return new Promise((resolve, reject) => {
        modules.bleManager.connect(
          target,
          p => progress.push(p),
          domainDevice => resolve({ device: domainDevice, progress }),
          err => reject(Object.assign(err instanceof Error ? err : new Error(String(err)), { progress }))
        );
      });
    },

    /**
     * Drives BleManager.writeDeviceConfiguration() directly. Resolves with the
     * board-reported settings the app reads back after the write.
     */
    writeConfiguration(deviceId, domainDevice) {
      return new Promise((resolve, reject) => {
        modules.bleManager.writeDeviceConfiguration(deviceId, domainDevice, resolve, reject);
      });
    },

    teardown() {
      while (stack.length) destroyTop();
      routerRecorder.setHandler(null);
      // installRadio(null) detaches the outgoing radio, cancelling its pending
      // timers so no stale GATT event reaches the next test in this file.
      installRadio(null);
      restoreClock();
      // The console stays captured for the lifetime of the file: the app's
      // best-effort OtsClient.close() logs after teardown returns, and restoring
      // here would dump that onto a passing run's output. ZEPP_TEST_LOG=1 still
      // shows everything.
    }
  };

  return harness;
}
