# Zepp OS end-to-end test harness

Runs the watch app's **real source** — pages, `BleManager`, `OtsClient`, `DeviceStore`,
the generated protobuf bindings — in Node, against a mocked `@zos/*` host and a
**virtual Snapshot PB board** that speaks the real OTS/protobuf protocol.

Headless, cross-platform, no network, no Zepp account, no GUI simulator.

```bash
cd app/zepp
npm install
npm test            # vitest run --config .test/vitest.config.js
npm run test:watch
```

The app's own code is the thing under test, and everything below it is
substituted: the seven `@zos` modules it imports, and the board on the other end of
the link.

## The `.test/` directory name

`zeus build` walks the entire app directory: it runs Rollup over every `.js` it
finds, and copies every other file into the packaged intermediate products inside
the `.zab`. A plain `test/` directory therefore **breaks the production build**
(Rollup follows the `vitest` import into Vite's own dist and dies) and, for
non-`.js` files, **ships the harness to the watch**.

Zeus skips dot-directories outright — verified empirically: `test/`, `tests/` and
`__tests__/` all break the build; with `.test/` the build succeeds and no harness
file appears anywhere in the produced `.zab`. Hence `.test/`, and
`--config .test/vitest.config.js` in the npm scripts.

If you move the harness, re-run `npm run build` and check the package:

```bash
unzip -l dist/*.zab                 # then unzip -l the .ip-package inside it
```

## Layout

```
.test/
  vitest.config.js      root = app/zepp, aliases the seven @zos modules to mocks/
  harness/index.js      startHarness(): installs the host, resets singletons, runs pages
  harness/flows.js      the ONLY place that knows this app's page urls and button labels
  mocks/
    runtime.js          pins protobufjs to the watch's byte representation (see below)
    globals.js          App / Page / getApp, the compressed clock, console capture
    zos-ui.js           records a widget tree; click_func handlers are the tap surface
    zos-router.js       records navigation and drives the page stack
    zos-storage.js      in-memory LocalStorage
    zos-device.js       480x480 device info
    zos-utils.js        px() identity
    zos-ble.js          the 11 mst* functions, forwarding to the installed radio
    zos-app.js          queryPermission / requestPermission, all authorized by default
  virtual-board/
    boardState.js       OTS object table, board fixture, generic protobuf encode/decode
    VirtualBoard.js     the OTS server: OACP/OLCP, object list, chunked ObjectData
    VirtualRadio.js     link layer: scan, connect, GATT routing, latency, faults, recording
    trace.js            trace capture and replay (ScriptedBoard, advertsFromCapture)
  e2e/*.test.js         the flows
```

## How the virtual board works

**Protocol.** Every object the board serves is encoded with `src/protocol/proto.js`
— the same generated bindings the watch runs, produced by `npm run build-proto`
from the shared `Configuration-Protocol` submodule the KMP and Wear clients build
against. Nothing here hand-rolls protocol bytes. CI regenerates the bindings before
running the suite, so a schema change moves both sides together and a genuine
incompatibility still surfaces as a decode failure.

**Wire format: BARE per-object messages.** Each OTS object is a raw `Device`,
`Profiles` or `DeviceConfig` — NOT wrapped in the `snapshotpb.v1.Message`
envelope. That is what the firmware and the KMP reference client exchange, and
what `OtsClient` decodes (see its `const { Device, DeviceConfig, Profiles }`
import). An enveloped board makes the client fail with
`invalid wire type ... at offset ...`, which is the first thing to check if every
read suddenly breaks.

**Nothing enumerates message fields.** Board state is keyed by OTS object and
stores exactly the properties object the generated encoder consumes:

```js
state = { device: {...}, deviceConfig: {...}, profiles: { profiles: [...] } }
```

Encoding is `<Type>.create` + `.encode`; applying a client write is
`<Type>.toObject(<Type>.decode(bytes), { defaults: true, ... })`. The object list
is a table in `boardState.js`:

```js
export const OTS_OBJECTS = [
  { name: 'device',        key: 'device',       type: Device,       writable: false },
  { name: 'profiles',      key: 'profiles',     type: Profiles,     writable: true },
  { name: 'device_config', key: 'deviceConfig', type: DeviceConfig, writable: true }
];
```

Adding an object to the protocol is one row here plus a key in the fixture —
`VirtualBoard` reads writability, selection order and encoding off the table. A
schema bump that adds or renames fields needs no board changes at all.

Because objects are bare, the board cannot tell a `Profiles` written into the
`device_config` slot from a valid `DeviceConfig`; it decodes whatever arrives as
the selected object's type, exactly as the firmware does.

**A write replaces the object — except DeviceConfig, which merges.** The OACP
WRITE op takes a WHOLE message, so a client that only means to change
`boot_text` necessarily sends the zero value for every other field.
`device_config.proto` defines those zeros as *leave the board's current value
alone*: `display_window` by message presence, `language` / `screen_brightness` /
`screen_off_delay` by their zero value (and a delay of 1000+ clamps to 999
rather than being rejected). The descriptor says so:

```js
{ name: 'device_config', …, mergeOnZero: ['displayWindow', 'language',
  'screenBrightness', 'screenOffDelay'], clampMax: { screenOffDelay: 999 } }
```

This matters for more than realism. A board that replaced wholesale would punish
exactly the behaviour the protocol asks for — `toProtocolConfig` omits a setting
the watch has no value for — and the test named for it could never actually check
it. `write-config.test.js` has both halves: the watch holding no display
settings must leave all four alone, and the watch holding them must write them.

**Fixture values are a fixed point.** `createProfile()` picks values that survive
`OtsModelMapper.clampProfileToFirmwareRanges()` unchanged, so a read/write round
trip returns what it sent: ASCII name of at most 10 bytes (`Profile.name` is a
nanopb `char[11]`, one byte reserved for NUL — an 11-byte payload fails the WHOLE
`Profiles` decode on the board), `eyeThreshold` within the 0-100 percent scale,
and nonzero values for every field where 0 means "select the firmware default".
`fire_rate_cap` counts TENTHS of a ball per second.

**Opcodes are NOT shared.** `VirtualBoard` spells out the OACP/OLCP opcodes and
result codes from the OTS spec rather than importing `src/protocol/OtsOpcodes.js`.
If the app's table drifts from the spec, the board must disagree with it — not
agree by construction.

**The exchange.** `VirtualBoard` holds an object list (`device`, `profiles`,
`device_config`) and answers:

- **OLCP (`2AC6`)** `FIRST` / `NEXT` / `LAST` / `PREVIOUS` — moves the selection
  pointer, replies `[0x70, opcode, result]`.
- **Characteristic reads** — Object Name (`2ABE`), Object Size (`2AC0`, current +
  allocated, uint32 LE), Object Id, Type, Properties, Feature. Delivered through
  the registered `mstOnCharaValueArrived` callback.
- **OACP (`2AC5`)** `READ` with offset/length — replies `[0x60, 0x05, result]`, then
  streams the object as `ObjectData` notifications, one ATT payload each (20 bytes
  by default). `WRITE` arms an accumulator; the client's `ObjectData` writes are
  reassembled and decoded back into board state.
- **Subscriptions** are enforced: a notification for a characteristic the client
  never subscribed to is dropped and recorded in `board.droppedNotifications`, so a
  test can prove the client subscribed rather than getting away with it. The
  client subscribes OACP/OLCP/ObjectData ONCE per connection in `OtsClient.prepare()`
  and keeps them until the link drops, rather than toggling around each object.

**Padding.** Firmware stores each object in a fixed-size flash slot and transfers
the whole slot, so reads come back zero-padded. The board pads to a 32-byte
boundary by default, which keeps `OtsModelMapper.trimProtobufPadding()` under test.
`boardOptions: { padAlignment: 0 }` turns it off.

**The link.** `VirtualRadio` is deliberately strict — a GATT operation against an
address with no live link fails, one issued before `mstBuildProfile` fails, and
with no radio installed at all the mock invokes `fail` rather than going silent
(a silent mock would hang `BleManager.disconnect`, which only calls back from
success/fail). Strictness is what makes the app's transport assumptions visible.
By default the radio reports 16-bit UUIDs in their canonical 128-bit form, the
style `OtsClient.uuidMatches()` is written for; `uuidFormat: 'short'` switches it.
`advertIntervalMs` makes it re-advertise continuously, the way a real Central
re-reports a peripheral many times a second.

**Every harness gets its own board address.** The app's singletons outlive a
single test. `BleManager.disconnect()` closes its `OtsClient` with
`close({ linkDropped: true })`: the client refuses every later GATT op and sends
no CCCD unsubscribe, because a late unsubscribe could reach a new link to the
same address. A GATT call that a stale client still makes resolves through the
module-level `ble.mst*` functions to whatever radio is installed WHEN IT RUNS —
the next test's. Distinct addresses make such a call harmless, exactly as if the
old client were talking to a board that walked away.

**The stale-close drain.** `startHarness` also waits one macrotask between
`bleManager.disconnect()` and `installRadio()`, so the outgoing client's
fire-and-forget `close()` chain resolves against a null radio rather than the
incoming one. Per-harness addresses make a stale call harmless; the drain makes
it impossible. Both are needed: every harness — including one given an explicit
`devices` list — must take a fresh `nextAddress()`.

**Teardown must stop the radio, not just unhook it.** `OtsClient` registers its
`mstOnCharaValueArrived` / `mstOnCharaNotification` callbacks once at module load
and they are never cleared, so a radio left running after `teardown()` would keep
delivering into the NEXT test in the same file — a stale `2AC0` read or `2AC5`
indication can satisfy an in-flight `_readChar`/`_awaitControlResponse` and make a
test pass for the wrong reason. `installRadio()` therefore calls `detach()` on the
outgoing radio, which drops its deliver hook and cancels every timer it scheduled.
`e2e/harness-isolation.test.js` pins this; it fails if the `detach()` call is
removed.

## The compressed clock

`OtsClient` is built out of real delays — 1s post-subscribe settles, 250ms chunk
pacing, 3/5/10s timeouts. The harness replaces the global `setTimeout` with one
that scales every app-scheduled delay by a constant factor (default `0.05`).
Ratios are preserved, so which timeout fires first is unchanged; only wall time
shrinks. Board latencies go through the same global, so they compress identically.
The harness's own `waitFor` polls on real time and is unaffected.

```bash
ZEPP_TEST_TIME_SCALE=1 npx vitest run --config .test/vitest.config.js
```

The vitest timeouts scale off the same env var, so no extra flags are needed. At
scale 1 the happy path takes ~7.6s per connect and a write round trip ~20s —
which is the real on-device cost, almost all of it the 1s post-subscribe sleeps
and the 250ms chunk pacing.

`ZEPP_TEST_LOG=1` un-silences the app's `console.log` chatter.

## Node vs. the watch: `mocks/runtime.js`

protobufjs picks its byte representation at load time. On Node it finds the global
`Buffer` and `Writer.finish()` returns a Node `Buffer`, whose `.slice()` is an
**alias** into protobufjs's shared 8192-byte allocation pool rather than a copy.
The watch has no `Buffer` and takes the `Uint8Array` path, where `.slice()` copies.

That difference is not cosmetic. `OtsClient._writeSelectedObjectBytes` chunks with
`bytes.slice(i, i + 20)` and hands the GATT layer `chunk.buffer`; under Node's
`Buffer` the app would transmit the whole 8KB pool. `mocks/runtime.js` forces the
`Uint8Array` path so the harness encodes and slices exactly as the device does.
Import it before anything that touches protobuf.

(Related latent hazard in production code: `_writeChar` sends `bytes.buffer || bytes`,
which is only correct when `bytes` is an exact, zero-offset view. It is today,
because every call site passes a `.slice()`. Passing a `finish()` result directly
would transmit the whole pool.)

## Fidelity — read this before trusting a green run

**These mocks encode assumptions about an undocumented API. They prove the app's
logic, not Zepp's radio.** The `@zos/*` surface has no published contract for
callback payload shapes; the `mst*` request/response shapes here were derived from
the app's own call sites (which defend against missing functions with
`typeof x === 'function'` guards, and read `res.address || res.dev_id`, `res.value`,
`res.characteristicUuid || res.charaUuid`). Where the app tolerates two shapes, the
harness picks one. Specifically unverified against hardware:

- scan result fields (`dev_id` / `address` / `rssi` / `service_data`)
- whether event `value` arrives as an `ArrayBuffer` (assumed), a typed array, or a
  plain array
- whether the stack reports canonical or short UUIDs (both are covered by tests)
- whether firmware really refuses to notify an unsubscribed characteristic
- the real ATT MTU, and firmware pacing tolerances
- how a watch with Bluetooth off reports a scan: the documented `mstStartScan`
  returns `false` when it cannot start, and the app also takes a `fail` call as
  the sign (`e2e/bluetooth-state.test.js`). CAUTION: the documentation gives
  `mstStartScan(callback, filter)`, but the app calls
  `mstStartScan(options, callback)`. A watch that follows the documentation can
  return `false` for each call, and the list then shows "Bluetooth is off" with
  Bluetooth on. Examine the return value on hardware
- the Bluetooth permission name: `device:bluetooth` comes from `app.json`, and
  the Zepp OS documentation gives no list of permission names, so a watch can
  report it as unknown (the app then scans as before)
- Zepp's page lifecycle: the harness fires `onInit` → `build` → `onResume` on first
  display, because `device_config` draws its entire body from `onResume` and is
  known to work on-device

A green suite means the app's logic, protocol framing and state machine are right.
It does not mean the app talks to a real board.

## Refreshing the mock shapes from real hardware

`BleManager.js` logs `JSON.stringify(res)` for every discovered device:

```js
console.log("Discovered device over BLE:", JSON.stringify(res));
```

Collect those lines from a real watch and feed them straight to
`advertsFromCapture()` in `virtual-board/trace.js` to replay a real scan. If the
real shape differs from what `VirtualRadio.startScan` emits, fix the radio — that
is the capture point for scanning.

For the GATT exchange, `VirtualRadio` records every client request together with
the responses it produced (`radio.script`), and `ScriptedBoard` replays such a
script in place of the simulated firmware:

```js
const scripted = new ScriptedBoard(recordedScript);
const h = await startHarness({ devices: [{ address, name: 'Snapshot PB', board: scripted }] });
```

`e2e/trace-replay.test.js` proves the seam by recording from the virtual board and
replaying the recording. To capture from real hardware instead, log the same
`{request, responses}` pairs at the `OtsClient` event handlers and
`ble.mstReadCharacteristic` / `mstWriteCharacteristic` call sites. A replay that
falls out of step reports `scripted.divergence` rather than silently producing the
wrong answer.

## Writing a test

```js
import { startHarness } from '../harness';

const h = await startHarness({ faults: { silentOacp: true } });
h.open('page/device_list/index');
h.click('Scan for Boards');
await h.waitForText('[#E:01]');
h.click('[#E:01]');
await h.waitForText('Connection Failed');
h.teardown();
```

App-specific knowledge — page urls, button labels, screen copy — lives only in
`harness/flows.js` (`PAGES`, `LABELS`, `SYNC_PROMPTS`, `STEP`, and the
`tapDiscoveredBoard` / `connectThroughPages` / `syncNewBoard` /
`seedDivergentLocalDevice` helpers). When the app's copy or page names change,
that file is the only thing that should need editing.

`startHarness(options)` takes `state` (board fixture), `boardOptions`
(`chunkSize`, `padAlignment`, `strictSubscriptions`), `faults`, `knownDevices`,
`devices`, `uuidFormat`, `latency`. The harness exposes `board`, `radio`, `ble`,
`store`, `ui`, `router`, `app` (the `@zos/app` permission recorder), `stack`, plus `open` / `click` / `clickWhere` / `back` /
`texts` / `hasText` / `waitFor` / `waitForText` / `connect` / `writeConfiguration` /
`profileUuid` / `deviceUuid` / `teardown`.

Fault keys: `connect: 'fail' | 'silent'`, `connectDelayMs`, `buildProfile: 'fail'`,
`olcpResult`, `oacpResult`, `silentOlcp`, `silentOacp`, `dropDataChunksAfter`,
`readError`, `writeError`, `chunkDelayMs`, `abortPendingConnectOnDisconnect`,
`scan: 'refuse' | 'fail'` (mstStartScan returns false, or calls `fail` with
`scanError`). The
fixture you pass is copied, so the same object can be reused across harnesses.

**Adding a page?** Register it in `harness/index.js`'s `PAGE_LOADERS`.
`e2e/app-manifest.test.js` fails if `app.json` and that map disagree.

**Adding a lifecycle hook to a page?** The host drives `onInit` → `build` →
`onResume` → `onDestroy` (`DRIVEN_HOOKS`). A page declaring any other `on*` hook
makes `open()` throw rather than silently skipping half its lifecycle — teach the
host to call it.

## Test isolation

App modules are imported once and cached, because `app.js` calls the global
`App({...})` and every page calls `Page({...})` at import time — those globals must
exist first, and an ES module body only runs once. Isolation therefore comes from
resetting the singletons (`bleManager`, `deviceStore`, `globalData`, the mocks),
not from re-importing them. Vitest gives each **file** a fresh module registry, so
anything a flow leaves in flight is contained to its file; `e2e/connect-cancel.test.js`
has its own file for exactly that reason.

The `@zos/ble` mock never clears its registered `mstOnCharaValueArrived` /
`mstOnCharaNotification` callbacks: `OtsClient` registers them once at module load,
and clearing them would silently break every later flow in the same file. Only the
radio behind them is swapped.

## Sync model

A read is classified against the **board baseline** the watch recorded
(`device.boardState`), not against the local copy:

- local edit, board unchanged -> **Synced**, and `deviceStore.isDiverged()` reports
  it as unsaved.
- board changed, no local edit -> **Synced**; the watch takes the board values
  with no prompt, because it has nothing to lose.
- board changed under a local edit -> **Conflict**.
- board not in the store -> **NewDevice**; confirming records the baseline.

`harness/flows.js` has one helper per case: `registerKnownDevice`,
`seedBoardDivergence` (mutates the BOARD), `seedLocalEdit` (mutates the STORE),
`seedConflict` (mutates both).

## Asserting on the app, not on the mock

A harness assertion is only worth something if it can fail. The board model
resets its OTS session on every disconnect (`VirtualRadio.disconnect` calls
`board.resetSession()`) whatever the app does — so `board.selectedIndex === -1`
and `board.subscriptions.size === 0` after a disconnect prove nothing about the
app. `teardown.test.js` therefore asserts on the CCCD writes the app itself
issued. A disconnect must issue none (the subscriptions end with the link), and
the closed client must refuse a later GATT op:

```js
h.radio.calls.filter(c => c.fn.startsWith('mstPrepare') && c.enable === false)
```

Stubbing `OtsClient.close()` to a no-op fails the suite, because the closed
client must refuse a later op.
Board-model checks are kept, but named as such.

When you add an assertion, ask which line of APP code would have to break for it
to fail. If the answer is "none", it is a board-model check — label it.

## Regressions

The domain device `id` is the protobuf `Device.device_id`, and the BLE layer
addresses a board by its MAC. `BleManager` tracks both (`connectedAddress` and
`connectedBoardId`), `isConnected()` accepts either, and a write uses the prepared
`OtsClient` of the live connection. `e2e/regressions.test.js` asserts this behaviour:

- After a connect, `device_config` shows the link as connected and shows Save to Board.
- A back step from `device_config` releases the BLE link.
- "Keep My Values" writes the values of the watch to the board.

The `mockDevicesEnabled` seam cannot show a fault here, because there the two ids
are equal.
