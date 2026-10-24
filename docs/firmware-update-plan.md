# Firmware update over Bluetooth

This document is the plan for a firmware update that the apps send to a board over
Bluetooth LE. It names the state of the board firmware and of the apps today, the design,
the work items in order, and the decisions that the owner must make. The firmware work
lands in the `SnapshotPB/Firmware` repository, and the app work lands here.

## 0. Goals

1. The app shows the firmware version of a board and the latest release for that board.
2. The user starts the update in the app. The app downloads the image, sends it to the
   board over Bluetooth LE, restarts the board, and reports the result.
3. The board refuses an image that is not for its hardware, and it goes back to the old
   image when the new image does not start.
4. The update uses the standard Zephyr tools, so a bench test needs no app.

## 1. State today

### 1.1 Board firmware

The board firmware (`Firmware` repository, Zephyr 4.4, ESP32-C3) already has the parts
that a safe update needs on the flash:

| Part | State | Source |
|---|---|---|
| Bootloader | MCUboot, built with sysbuild | `app/sysbuild.conf`, `app/sysbuild/mcuboot.conf` |
| Upgrade mode | Overwrite-only, the Espressif default. MCUboot copies `slot1` over `slot0` and keeps no copy of the old image, so **there is no revert** | `deps/zephyr/soc/espressif/Kconfig.sysbuild`; MCUboot has `CONFIG_BOOT_UPGRADE_ONLY=y` |
| Bootloader watchdog | None. `config_wdt()` of hal_espressif disables the RWDT and the MWDT0 flash-boot protection, and MCUboot has `CONFIG_WATCHDOG=n` | `hal_espressif` `zephyr/common/soc_init.c` |
| Slots | `slot0` at `0x20000` and `slot1` at `0x1e0000`, 1792 KB each; `scratch` 124 KB; `storage` 192 KB | `dts/vendor/espressif/partitions_0x0_default_4M.dtsi` |
| Image size | 898 KB (`snapshot_v2-2026.43.1-app.signed.bin`) | Release `v2026.43.1` |
| Signature | None. MCUboot checks a hash only. `CONFIG_MCUBOOT_GENERATE_UNSIGNED_IMAGE=y` | `docs/versioning-and-release-plan.md` §1.1, §6 |

The firmware has **no update transport**. `prj.conf` sets no `CONFIG_MCUMGR*` option. The
only GATT service is the vendor Object Transfer Service (OTS) for the configuration
objects. The OTS write path takes a whole object into RAM, and the object buffer is about
430 bytes (`OBJ_MAX_SIZE`). The app writes it in 20-byte chunks with a 250 ms pause. That
path cannot carry a 900 KB image.

Other facts of the firmware that the design uses:

- `CONFIG_BT_MAX_CONN=1`, `CONFIG_BT_SMP=y`, `CONFIG_BT_SETTINGS=y`. The Android app asks
  for an ATT MTU of 517, but the board answers 65: the minimum of `CONFIG_BT_L2CAP_TX_MTU`
  (65) and `CONFIG_BT_BUF_ACL_RX_SIZE` (69) minus 4. `ots.c` streams at `MTU - 3`.
- `CONFIG_BT_GATT_CACHING` and `CONFIG_BT_GATT_SERVICE_CHANGED` are on. A new service
  changes the database hash, and a bonded client gets Service Changed.
- The radio is off at every boot. The user turns it on in the board menu.
- A lockout takes the radio down (`menu_config_backend_fc.c`), so a locked board gets no
  update.
- A flash write locks the interrupts. The firmware runs its NVS writes on a separate
  work queue for this reason (`GEMINI.md`, thread design). An image write to `slot1`
  has the same effect, for about 900 KB.
- The advertisement carries the firmware version in 4 bytes (`[year-2000, week, count,
  flags]`, bit 0 of flags is a dev build) and the board type `Cocker`. It does **not**
  carry the hardware revision. The `Device` proto has `model` (autococker), not the
  board target.
- Two hardware targets exist: `snapshot_reva` and `snapshot_v2`. The sear gate is on a
  different pad on each, so an image for the other target drives the wrong pin
  (release manifest `warning`). The bench boards are `esp32c3_devkitm`.
- `fire_control_shutdown()` and `fire_control_reboot()` exist. The cell monitor can read
  the cell voltage.

### 1.2 Firmware releases

`release.yml` cuts a tag `v<year>.<week>.<count>` and `build-release.yml` publishes a
GitHub Release in `SnapshotPB/Firmware` for `snapshot_v2` only. The assets of a release:

```
snapshot_v2-<version>-app.signed.bin        the update image (MCUboot header, no signature)
snapshot_v2-<version>-manifest.json         version, commit, board, compatible_hardware, sha256 of each asset
snapshot_v2-<version>-SHA256SUMS
snapshot_v2-<version>-merged.hex            bootloader + app, for a USB flash
snapshot_v2-<version>-mcuboot.bin, -app.elf, -app.map, -manual-document.json
```

The manifest holds `compatible_hardware: ["snapshot_v2"]`. A devkit image gets an empty
list.

**The `Firmware` repository is private.** An app in the field cannot read its releases
without a token, and a token in a public app is a leak. The app repository
(`Configuration-App`) and the protocol repository are public. The website
(`snapshotpb.com`) is a static site on S3 and CloudFront, and its deploy already
downloads a release asset of the app repository.

### 1.3 Apps

- `core:bluetooth` has the OTS client on BlueFalcon 3.7.14. BlueFalcon has
  `writeCharacteristic(peripheral, characteristic, bytes, CharacteristicWriteType.WithoutResponse)`,
  `changeMTU()` and `BluetoothPeripheral.mtuSize`. The Android seam negotiates an MTU of
  517 in `onServicesDiscovered`. The web engine `JsEngine` calls `writeValue()`, which
  Chrome sends with a response when the characteristic has the write property
  (`EmulatorEngine` KDoc).
- The apps have **no HTTP client**. The Android manifest has no `INTERNET` permission.
  The desktop app reads GitHub Releases through the Nucleus updater only.
- `AdvertisementData.firmwareVersion` is a `FirmwareVersion(year, week, count, dev)`
  with a natural order. `DeviceBrowserScreen` shows it on each device card.
- `DebugBoard` / `FakeBoard` give a simulated board with no radio for the Android Demo
  entry and the iOS `--fake-board` argument.
- The Zepp app uses the `hmBle.mst*` API. It has no file download and a slow BLE path.
- The same-session reconnect on Linux is a known BlueFalcon defect (`RpiEngine`,
  upstream PR 310). The step that verifies the board after its restart must tolerate a
  failed reconnect.

## 2. Design

### 2.1 Transport: MCUmgr SMP over Bluetooth LE

The firmware gets the Zephyr MCUmgr "Simple Management Protocol" (SMP) service with the
image group and the OS group. The configuration keeps the OTS service. The two services
share the one link.

Reasons for SMP, and not an OTS object:

- `img_mgmt` writes each chunk to `slot1` at its offset with `flash_img`, so no image
  buffer is in RAM. An upload can resume at an offset after a lost link.
- The firmware checks the image hash and the header before it marks the slot.
- The `mcumgr` CLI and the nRF Connect Device Manager apps speak it, so a bench test
  needs no app, and the firmware side is testable before the app side exists.
- Only a Kconfig fragment and a small hook are new on the board.

The firmware configuration is the fragment `app/ota.conf`. The release build does not
apply it until items 3 and 5 land (§4, decision 2). A bench build applies it with
`./build.sh -- -Dapp_EXTRA_CONF_FILE=ota.conf`. The comments in the fragment give the
reason for each option.

```
CONFIG_NET_BUF=y
CONFIG_ZCBOR=y                                # needs zcbor in the name-allowlist of west.yml
CONFIG_MCUMGR=y
CONFIG_MCUMGR_TRANSPORT_BT=y
CONFIG_MCUMGR_TRANSPORT_BT_REASSEMBLY=y
CONFIG_MCUMGR_TRANSPORT_BT_PERM_RW=y          # the default is RW_AUTHEN with BT_SMP; §4, decision 5
CONFIG_MCUMGR_TRANSPORT_BT_CONN_PARAM_CONTROL=y
CONFIG_MCUMGR_TRANSPORT_BT_CONN_PARAM_CONTROL_MIN_INT=12   # 15 ms, the iOS minimum
CONFIG_MCUMGR_TRANSPORT_BT_CONN_PARAM_CONTROL_MAX_INT=24   # 30 ms; iOS needs max >= min + 15 ms
CONFIG_MCUMGR_TRANSPORT_BT_CONN_PARAM_CONTROL_TIMEOUT=400  # 4 s, because a flash erase stalls the radio
CONFIG_MCUMGR_TRANSPORT_NETBUF_SIZE=2475      # five ATT writes of 495 B in one SMP packet
CONFIG_MCUMGR_TRANSPORT_WORKQUEUE_THREAD_PRIO=10   # the default 3 is above the fire-control band
CONFIG_MCUMGR_TRANSPORT_WORKQUEUE_STACK_SIZE=4096  # measure on the bench, then trim
CONFIG_MCUMGR_MGMT_NOTIFICATION_HOOKS=y       # the three hooks below depend on it
CONFIG_IMG_MANAGER=y
CONFIG_STREAM_FLASH=y
CONFIG_MCUMGR_GRP_IMG=y
CONFIG_IMG_ERASE_PROGRESSIVELY=y              # one 4 KB sector erase for each chunk boundary
CONFIG_MCUMGR_GRP_IMG_UPLOAD_CHECK_HOOK=y
CONFIG_MCUMGR_GRP_IMG_STATUS_HOOKS=y
CONFIG_MCUMGR_GRP_IMG_SLOT_INFO=y
CONFIG_MCUMGR_GRP_OS=y
CONFIG_MCUMGR_GRP_OS_INFO=y                   # `os info -f i` reports CONFIG_BOARD_TARGET, see §2.2
CONFIG_MCUMGR_GRP_OS_RESET_HOOK=y
CONFIG_MCUMGR_GRP_OS_BOOTLOADER_INFO=y
CONFIG_MCUMGR_GRP_OS_MCUMGR_PARAMS=y          # the client reads the buffer size
CONFIG_BT_L2CAP_TX_MTU=498
CONFIG_BT_BUF_ACL_RX_SIZE=502
CONFIG_BT_BUF_ACL_TX_SIZE=251                 # the largest link-layer payload
```

The SMP work queue runs at preemptible priority 10, the level of the OTS work queue. A
`BUILD_ASSERT` in `include/app/thread_schedule.h` keeps it below the fire-control band.
The fragment costs 36 KB of SRAM on `snapshot_v2` (60.7 % to 70.6 % of `dram0_0_seg`) and
about 19 KB of flash. The image stays at 49 % of the slot. The larger MTU also changes
OTS: one notification can hold a whole object.

The SMP service UUID is `8D53DC1D-1DB7-4CD3-868B-8A527460AA84` and its one
characteristic is `DA2E7828-FBCE-4E01-AE9E-261174997C48` (write without response, notify).
The scan filter of the app stays on `0xFFE2`; the SMP UUID goes into the GATT allowlist
of the web engine (`platform/browser/bluetooth`, the scan filters double as the allowlist).

### 2.2 Which image fits which board

The board reports its own hardware target. `os info` with the format `i` returns
`CONFIG_BOARD_TARGET`, for example `snapshot_v2/esp32c3` (`os_mgmt.c`). The app compares
the part before the first `/` with the manifest. The format `p` returns the processor
name. The option for the short form is deprecated, so the firmware does not set it. This
needs no change to the protocol schemas. The app keeps the target of a connected board in memory
(`BluetoothDeviceState`), not in the device file.

The rule of the app: it offers an image only when the `compatible_hardware` list of the
manifest contains the target that the board reported. A board that does not answer
`os info` (older firmware without SMP) gets no offer, and the app says that the board
needs a USB flash of a firmware with the update service.

The version check uses `FirmwareVersion` from the advertisement and the `version` of the
manifest (`2026.43.1`, a 4-digit year; the advertisement year is `2000 + byte`). The app
marks a release "newer" when its version is greater than the version of the board. A
board on a dev build (`flags & 1`) with the same numbers is lower than the release.

Only the Android app fills `advertisementData`. On iOS and the desktop, the board version
comes from the MCUboot version in `image list`, which has no dev flag. There, a dev build
reads as the release with the same numbers, and the app shows it as up to date.

### 2.3 Where the app reads the releases

The `Firmware` repository publishes a static site on GitHub Pages. The plan of that site
is `docs/firmware-distribution-pages-plan.md` in the `Firmware` repository. The site is a
function of the GitHub Releases: a deploy reads every Release and writes the whole site
again. The site is public, although the repository is private.

```
https://snapshotpb.github.io/Firmware/index.json                   the boards and their channels
https://snapshotpb.github.io/Firmware/<board>/index.json           every release of the board, newest first
https://snapshotpb.github.io/Firmware/<board>/latest.json          the newest stable release
https://snapshotpb.github.io/Firmware/<board>/<version>/<asset>    the image, the manifest, the SHA256SUMS
```

Each release entry holds `version`, `tag`, `channel` (`stable`, or `beta` for a GitHub
pre-release), `compatible_hardware`, `warning`, and an `assets` map. Each asset has
`name`, `path`, `url`, `size` and `sha256`. The app reads `latest.json`, downloads
`assets.image.url`, calculates the SHA-256, and compares it with `assets.image.sha256`
before it sends one byte to the board. The site answers with
`access-control-allow-origin: *`, so the web app can read it.

The owner holds a release back with the pre-release flag on GitHub, and removes one with
a delete. The site follows at the next deploy. A custom domain
(`firmware.snapshotpb.com`) is a decision of the firmware plan; the app keeps the base
URL in one constant.

### 2.4 Safety on the board

- **The board holds fire control during the upload.** The `img_mgmt` upload check hook
  (`MGMT_EVT_OP_IMG_MGMT_DFU_CHUNK`) refuses the first chunk unless the board is in a
  safe state. The first accepted chunk puts the fire control into a hold: coils off,
  trigger ignored, panel text `Update…`, and the task watchdog channels of the threads
  that the flash writes stall get a longer reload or a pause. A stopped upload (status
  hook `DFU_STOPPED`, or a link loss) releases the hold. The hold is a new driver
  operation, next to `fire_control_shutdown()`. See §4, decision 3.
- **The board refuses an update on a low cell.** The hook also reads the cell voltage
  and refuses below a threshold that the owner sets.
- **The board refuses an image for another target.** `img_mgmt` checks the image header
  and the hash. The image header of MCUboot has no board name, so the app-side rule of
  §2.2 is the check for the target. A future firmware can add the board name as a TLV
  (`imgtool --custom-tlv`) and check it in the upload hook.
- **Revert.** The bootloader is overwrite-only today, so `image test` and `image confirm`
  have the same result: the new image is permanent. A revert needs
  `SB_CONFIG_MCUBOOT_MODE_SWAP_USING_MOVE=y` in `app/sysbuild.conf`. That replaces the
  bootloader, so it lands with the signature of item 5 in the same USB flash of each
  board. A test build of `snapshot_v2` with swap using move builds (MCUboot 35,040 B), but
  Espressif chose overwrite-only as the default, so the bench must prove the swap mode.
  With swap using move, the app marks the uploaded image as `test` and resets the board.
  MCUboot swaps the images at that boot. The new image runs once. When nobody confirms
  it, MCUboot swaps back at the next reset. Who confirms is decision 4 in §4.
- **The copy is slow and the panel is dark.** MCUboot drives no display. The board is
  dark while MCUboot copies (overwrite-only) or swaps (swap using move, each 4 KB sector
  written two times) the 900 KB image. On an `esp32c3_devkitm`, the swap using move took
  about 18 s from the reset to the boot of the new image (three runs). The app shows a
  "do not remove the battery" step with 30 s (`EXPECTED_SWAP_MILLIS`). Measure the swap
  on a `snapshot_v2` board too. No hardware watchdog runs in MCUboot,
  so a long copy gets no watchdog reset, but a hang in MCUboot gets no reset either.
- **Signature.** A board with the current bootloader accepts any image with a correct
  hash. The ECDSA P-256 signature of the firmware plan §6 must be on before the first
  release that the apps offer in the field. The switch changes the bootloader, so each
  board gets one USB flash of the merged hex at that point (firmware plan §6.1).
- **Downgrade.** Version 1 of the feature offers one release per channel, so the app
  never offers an older release. MCUboot downgrade prevention
  (`MCUBOOT_DOWNGRADE_PREVENTION`) stays off, so a bench board can go back with a USB
  flash or with the `mcumgr` CLI.

### 2.5 Bench check before the app work

A bench test proves the firmware side with the standard tools, so the app work starts on
a known-good board:

```
./build.sh -- -Dapp_EXTRA_CONF_FILE=ota.conf      # build the bench image; raise app/VERSION for the image you upload
mcumgr --conntype ble --connstring peer_name='Snapshot PB' image list
# os info with format "i"; expected answer: snapshot_v2/esp32c3. The Go mcumgr CLI may
# have no `os info` command; then use smpmgr, AuTerm or nRF Connect Device Manager.
mcumgr --conntype ble --connstring peer_name='Snapshot PB' image upload build/firmware/app/zephyr/zephyr.signed.bin
mcumgr --conntype ble --connstring peer_name='Snapshot PB' image test <hash of slot1>
mcumgr --conntype ble --connstring peer_name='Snapshot PB' reset
# the radio is off at every boot: turn Bluetooth on in the board menu, then:
mcumgr --conntype ble --connstring peer_name='Snapshot PB' image list
mcumgr --conntype ble --connstring peer_name='Snapshot PB' image confirm   # swap using move only
```

Build the uploaded image with `ota.conf` too. An image without the fragment removes the
update service from the board. Record the upload time, the copy or swap time, the
duration of the radio stall at each sector erase, and the result of a power cut during
the upload and during the copy. Read the OTS configuration objects at the new MTU of 498.
Record the frames of each command for the app tests (item 9).

### 2.6 App architecture

**Modules.**

- `core:model` gets `FirmwareChannel`, `FirmwareManifest`, `FirmwareUpdateProgress`
  and `HardwareTarget`. `FirmwareVersion` already exists.
- `core:bluetooth` gets a package `smp`: `SmpClient` (the 8-byte header, the sequence
  number, one request in flight, the reassembly of a notification stream), `SmpCbor`
  (kotlinx-serialization-cbor), `ImageManagement` (`list`, `upload` with the offset
  loop, `test`, `confirm`, `erase`) and `OsManagement` (`info`, `reset`, `mcumgrParams`,
  and the board target from `info("i")`). The upload uses
  `CharacteristicWriteType.WithoutResponse` and waits for the `off` of each response
  before the next chunk, as `img_mgmt` demands. One SMP packet has the `buf_size` that
  `mcumgrParams` reports (2475 bytes with `ota.conf`), and the client splits it into ATT
  writes of `maximumWriteValueLength`. The board joins them (`..._BT_REASSEMBLY`). Thus
  the client waits for one response for each five writes at an MTU of 498. A board that
  does not answer `mcumgrParams` gets packets of 384 bytes, the Zephyr default.
- A new module `core:firmware` holds `FirmwareFeed` (reads `latest.json` or `index.json`
  of the board, downloads and checks the image) and `FirmwareUpdateCoordinator` (the state
  machine of §2.7). It depends on `core:model` and `core:bluetooth`. The HTTP client is
  Ktor 3 behind an interface, with the engines OkHttp (Android), CIO (JVM), Darwin (iOS)
  and Js (wasmJs). `core:di` binds them.
- The Android manifest gets `android.permission.INTERNET`.

**Requests and state.** `BluetoothRequest` gets `UpdateFirmware(deviceId, image, requestId)`
and `ConfirmFirmware(deviceId)`. `BluetoothStateManager` gets a
`StateFlow<FirmwareUpdateProgress>`. The engine takes `writeMutex` for the whole upload,
so no OTS write runs at the same time, and it does not re-read the configuration while
the board is in the hold. The simulated board (`FakeBoard`) answers the new requests
with a fake upload, so the Demo entry shows the whole flow with no hardware.

The engine reads `os info` and `mcumgr params` after the 4-step connect flow, not as a
fifth step. It does this only when the discovered services contain the SMP service. A
board without SMP, or an SMP error, gives a `null` target and no connection failure.

**Release gate.** Decision 2 in §4 is a constant of `core:firmware`: until item 5 is in
a firmware release, a release build of the app shows the board version and target but
no "Update" button. A debug build and the local site of item 12 show the button.

**Screens.** `ConfigureDeviceScreen` gets a "Firmware" section with the board version,
the hardware target, the latest release of the channel, and an "Update" button. The
button is active only when the board is connected, the release is newer, and the
target fits. A new `ScreenKeyFirmwareUpdate(deviceId)` screen shows the steps of §2.7
with a progress bar, the warnings (keep the marker still, do not remove the battery) and
the result. New strings go into `values/strings.xml` and every `values-*` locale.

**Platforms.** Version 1 ships on Android, iOS, and the desktop on Windows and Linux.
In BlueFalcon 3.7.14, `MacosJvmEngine` and `JsEngine` have no typed write, so the SMP
transport stops with `SmpWriteUnsupported` there. The section stays, with the reason
`PLATFORM_UNSUPPORTED` and no active "Update" button. macOS desktop
follows when `MacosJvmEngine` gets a typed write; its seam must then also discover the
characteristics of the SMP service, as it does for OTS. `RpiEngine` reports no MTU, so
Linux sends 20-byte writes, and the upload is slow. The web app follows when its
engine writes without a response (a BlueFalcon change, or the `writeValueWithoutResponse`
call in our own engine); with a response the upload takes several minutes. The viewer
emulator has no SMP service, so the section shows the reason `NO_UPDATE_SERVICE` there.
The Wear app gets the feature
later, if at all: the phone is the better place for a 900 KB download. The Zepp app does
not get it.

**Sync.** The hardware target stays out of the device file, so `DEVICE_SYNC_SCHEMA_VERSION`
does not change. The firmware version is already in `advertisementData`, which the
phone and the watch sync.

### 2.7 The update flow in the app

1. **Check.** The app reads `latest.json` of the board target. It compares the version
   and the target. It shows the result in the Firmware section.
2. **Download.** The app downloads the image and checks the SHA-256. A bad hash stops
   the flow.
3. **Prepare.** The app checks that the board is connected, reads `image list` and
   `os info`, and shows the warnings. The user confirms.
4. **Upload.** The app sends the image in chunks and shows the progress. The board holds
   fire control. A lost link stops the flow with a "try again" result. A new attempt
   sends the first chunk at offset 0 with the same `sha` and `len`. When the board still
   holds that upload, it answers with its offset, and the upload continues there.
   `image list` does not report an upload offset. A board that restarted has no upload
   state, so the upload starts again at offset 0.
5. **Test and reset.** The app marks the image as `test` and sends `reset`. The board
   disconnects. The app shows the "swap in progress" step with the measured time.
6. **Verify.** The app scans for the board. When the advertisement shows the new
   version, the app connects, reads the configuration as on every connect, and confirms
   the image (if the firmware does not confirm itself, decision 4). With swap using move
   (item 5), an advertisement with the old version means that MCUboot reverted the image,
   and the app says so. With the overwrite-only bootloader of today, it means that the
   copy did not occur.
   When no advertisement comes, the app tells the user to turn the radio on in the board
   menu, because the radio is off at every boot.

## 3. Work items in order

| # | Repository | Item |
|---|---|---|
| 1 | Firmware | Add the MCUmgr configuration of §2.1 and build it for `esp32c3_devkitm` and `snapshot_v2`. Check the RAM and flash budget and the thread priorities (`include/app/thread_schedule.h`). Add a CI leg that builds `ota.conf`, so the fragment stays current. |
| 2 | Firmware | Run the bench check of §2.5 on a devkit. Record the upload time, the copy time, the radio stall of a sector erase and the power-cut results in `docs/`. Record the SMP frames for item 9. |
| 3 | Firmware | Add the fire-control hold and the upload check hook of §2.4. Add a twister test for the hook logic on `native_sim`. |
| 4 | Firmware | Decide and implement the confirm policy (§4, decision 4). |
| 5 | Firmware | Turn on the ECDSA P-256 signature (firmware plan §6) and swap using move (§2.4, revert). Prove the swap and the revert on the bench, then reflash the bench boards. |
| 6 | Firmware | Publish the GitHub Pages site of `docs/firmware-distribution-pages-plan.md` (`scripts/pages_index.py`, `pages.yml`, the dispatch from `build-release.yml`). |
| 7 | Firmware | Owner enables Pages with the source "GitHub Actions" and the tag rule of the `github-pages` environment. |
| 8 | App | `core:model` types and `FirmwareVersion` comparison with the manifest version. Unit tests. |
| 9 | App | `core:bluetooth` SMP client with unit tests against frames that the `mcumgr` CLI recorded. |
| 10 | App | `core:firmware` feed and coordinator, Ktor, DI, `INTERNET` permission. Fake feed and fake SMP in `FakeBoard`. |
| 11 | App | Engine requests, progress state, screens, strings in every locale. |
| 12 | App | Bench test on the devkit with a local site (a `--firmware-site-url` flag or a debug setting that points to a local `index.json`), then on a `snapshot_v2` board. Instrumented test on Android in the style of `OtsSessionE2ETest`. |
| 13 | App | Web engine write without response, then the web target. |

Items 1 to 5 need no app work. Items 8 and 9 need only the recorded frames from item 2.

## 4. Decisions for the owner

1. **Release channel.** The GitHub Pages site of the `Firmware` repository (§2.3). The
   open points of that site (the binaries on it, a custom domain, a signed index, the
   retention) are decisions in the firmware plan.
2. **Signature first.** The recommendation is that no app release offers an update
   before item 5 lands. Until then, the bench uses a local site.
3. **Fire-control hold.** The recommendation is a hard hold (coils off, trigger ignored)
   for the whole upload, with a refusal on a low cell. The alternative is a warning in
   the app only.
4. **Who confirms the new image.** Option A: the firmware confirms itself after it
   reaches the home screen with every subsystem up and the watchdog has run for a set
   time. Option B: only the app confirms, after the verify step. Option A survives a
   user who updates and then walks away. Option B catches a build whose radio is broken.
   The recommendation is A plus an app confirm when the image is still in `test`.
5. **SMP permission.** `MCUMGR_TRANSPORT_BT_PERM_RW` lets any central write. The
   `_AUTHEN` variant needs a pairing with a passkey, and the board has no display input
   for one. With the signature of item 5, an unsigned image cannot start, so `RW` is
   acceptable. The recommendation is `RW` with the signature.
6. **Reinstall.** Whether the Firmware section offers "install again" for the same
   version, for a board on a dev build or after a revert.

## 5. Risks

- **Flash writes stall the CPU.** The hold of §2.4 removes the firing hazard, but the
  display and the watchdog channels still see the stalls. Item 2 measures them.
- **A flash erase stalls the radio.** Each sector erase runs `spi1_start()` with the cache
  off inside a full `irq_lock()`, so the interrupt of the Bluetooth controller waits too.
  The Zephyr port has no flash yield and no auto-suspend. One 4 KB erase takes about
  45 ms, and some hundred ms in the worst case of the flash data sheet. The progressive
  erase keeps each stall to one sector, and the connection request of the fragment asks
  for a supervision timeout of 4 s. The app must not send `image erase` before an upload,
  because it uses 64 KB block erases. Item 2 measures the stall.
- **The swap time.** A board that is dark for a minute looks dead. The app must show the
  measured time, and the manual must say it.
- **A power cut during the copy or the swap.** MCUboot starts the copy again, or resumes a
  swap using move, after a power cut. Item 2 tests the copy, and item 5 tests the swap.
- **Reconnect after the reset.** On Linux the same-session reconnect is a known defect.
  The verify step must offer a manual retry.
- **Bandwidth of the site.** GitHub Pages has a soft limit of 100 GB per month. A check
  reads `latest.json` (about 2 KB). The app downloads the image only when the user
  starts an update, and it keeps the download for a second attempt.
- **The dev signing key.** The firmware branch `feat/mcumgr-ble` signs each build with
  `app/keys/dev-ec-p256.pem`, which is in the repository. The signature stops a foreign
  image only while that key stays secret. The `Firmware` repository is private, but each
  person with read access can sign an image. Decision 5 (`RW`) is safe in the field only
  with a production key that is not in the repository.
- **No hardware target in the advertisement.** Until the board answers `os info`, the
  app cannot tell `snapshot_reva` from `snapshot_v2`. The app offers nothing to a board
  without SMP.
