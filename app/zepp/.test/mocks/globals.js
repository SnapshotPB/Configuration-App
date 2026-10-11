// Zepp OS hands every mini-program a set of globals (App/Page/getApp) plus the
// usual ES timer/console globals. Node provides none of the Zepp ones, so the
// harness installs them before any app module is imported.

// Captured before installClock() swaps the global in — the harness's own waiting
// helpers must run on real wall-clock time even while app code runs compressed.
export const realSetTimeout = globalThis.setTimeout;
export const realClearTimeout = globalThis.clearTimeout;

const DEFAULT_TIME_SCALE = Number(process.env.ZEPP_TEST_TIME_SCALE || 0.05);

let appInstance = null;
let initialGlobalData = null;
let lastPageDefinition = null;
let clockInstalled = false;
let activeScale = DEFAULT_TIME_SCALE;

const consoleRecord = [];
let realConsoleLog = null;

// --- App / Page / getApp ----------------------------------------------------

function App(definition) {
  appInstance = { ...definition };
  // Snapshot the declared globalData so resetGlobalData() can restore it without
  // re-importing app.js (ES module bodies only ever run once).
  initialGlobalData = JSON.parse(JSON.stringify(definition.globalData || {}));
  appInstance.globalData = JSON.parse(JSON.stringify(initialGlobalData));
  if (typeof appInstance.onCreate === 'function') appInstance.onCreate({});
  return appInstance;
}

function Page(definition) {
  lastPageDefinition = definition;
  return definition;
}

function getApp() {
  return appInstance;
}

/** Definition captured by the most recent `Page({...})` call, then cleared. */
export function takeLastPageDefinition() {
  const def = lastPageDefinition;
  lastPageDefinition = null;
  return def;
}

export function resetGlobalData() {
  if (appInstance && initialGlobalData) {
    appInstance.globalData = JSON.parse(JSON.stringify(initialGlobalData));
  }
}

export function installGlobals() {
  globalThis.App = App;
  globalThis.Page = Page;
  globalThis.getApp = getApp;
}

// --- Clock ------------------------------------------------------------------

// The OTS client is built out of real delays: 1s post-subscribe settles, 250ms
// chunk pacing, 3/5/10s timeouts. Running those at wall-clock speed would make
// the suite take minutes, so the harness scales every app-scheduled timer by a
// constant factor. Ratios (and therefore which timeout fires first) are
// preserved; only the wall time shrinks. Run with ZEPP_TEST_TIME_SCALE=1 to
// exercise the flows at true hardware pacing.
export function installClock(scale = DEFAULT_TIME_SCALE) {
  // Re-installing with a different scale must take effect: a harness may pass
  // options.timeScale, and a missed teardown must not silently pin the old one.
  activeScale = scale;
  if (clockInstalled) return;
  clockInstalled = true;
  globalThis.setTimeout = (fn, ms, ...args) =>
    realSetTimeout(fn, Math.max(0, Math.round((ms || 0) * activeScale)), ...args);
  globalThis.clearTimeout = realClearTimeout;
}

export function restoreClock() {
  activeScale = DEFAULT_TIME_SCALE;
  if (!clockInstalled) return;
  clockInstalled = false;
  globalThis.setTimeout = realSetTimeout;
  globalThis.clearTimeout = realClearTimeout;
}

/** The scale currently driving the global clock. */
export function timeScale() {
  return activeScale;
}

// --- Console ----------------------------------------------------------------

// The app logs heavily on every BLE step. Captured rather than printed unless
// ZEPP_TEST_LOG=1, so a failing assertion isn't buried in transport chatter.
export function installConsole() {
  if (realConsoleLog) return;
  realConsoleLog = console.log;
  if (process.env.ZEPP_TEST_LOG === '1') return;
  console.log = (...args) => {
    consoleRecord.push(args.map(String).join(' '));
  };
}

export function restoreConsole() {
  if (!realConsoleLog) return;
  console.log = realConsoleLog;
  realConsoleLog = null;
}

export function logs() {
  return consoleRecord.slice();
}

export function resetLogs() {
  consoleRecord.length = 0;
}
