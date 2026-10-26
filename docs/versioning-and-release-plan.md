# Versioning and release

This document is the reference for the app version and for the release workflows. Code
comments cite its section numbers, so each section keeps the number that the code
cites. That is why some numbers are not used.

## 0. Goals

The version and release system has these five properties. Code comments cite them by
number.

1. The version comes from the calendar. Nobody edits a version by hand. One version
   string goes to every target (§3).
2. A GitHub Action cuts a release. A developer does not edit a file to cut a release (§5).
3. The git tag is the single source of truth. The build workflow gets the version from
   the tag, and it builds and publishes every target (§5, §6).
4. The desktop app updates itself from GitHub Releases through the Nucleus updater
   (§6.1, §10).
5. A dev build and a release build are always different. A dev build has the `-dev`
   marker. Only the build workflow, on a clean checkout of a tag, publishes a clean
   release version (§4).

## 3. Version scheme

The version has the form `<year>.<week>.<count>`:

- `year` is the last two digits of the ISO-8601 week-year.
- `week` is the ISO-8601 week, from 1 to 53.
- `count` is the number of the release in that week. The first release in a week has
  the count 1.

The first release in ISO week 29 of 2026 is `26.29.1`. The second release in that week
is `26.29.2`. The git tag adds a `v`: `v26.29.1`.

```
human / git tag / GitHub Release / all stores : 26.29.1[-dev...]
SemVer (Nucleus updater, desktop packageVersion): 26.29.1
git tag name                                    : v26.29.1
```

**Per-target values.** One version string gives all of these values:

| Target | String field | Integer field | Value for `26.29.1` |
|---|---|---|---|
| Desktop (Nucleus `packageVersion`) | `26.29.1` | — | The updater compares this value |
| Android `app` | `versionName = 26.29.1` | `versionCode` | `2629001` |
| Wear | `versionName = 26.29.1` | `versionCode` | `2629001` |
| iOS | `MARKETING_VERSION = 26.29.1` | `CURRENT_PROJECT_VERSION` | `2629001` (see the iOS rule below) |
| Zepp (`app.json`) | `26.29.1` | version code | `2629001` |

**Version constraints.** The version must obey three constraints at the same
time. Only a 2-digit year obeys all three:

| Constraint | Source | Rejects |
|---|---|---|
| `MAJOR ≤ 255` (`MAJOR.MINOR.BUILD`: minor ≤ 255, build ≤ 65535) | jpackage MSI validator | `2026.29.1` on Windows |
| Only numbers: `MAJOR[.MINOR][.PATCH]` | jpackage DMG and MSI | a `-dev+<sha>` suffix |
| The manifest version **must be equal** to the tag without its `v` | Nucleus `GitHubProvider` download URL (§10) | a version override for one format |

jpackage checks these constraints when Gradle configures the project. Thus a bad version
fails `./gradlew help`, `lint` and every CI job, not only the desktop package tasks.
`packagingVersion()` removes the `-dev` suffix for `packageVersion` only (§4), so a dev
build can configure.

Do not set a different version for one format, for example `msiPackageVersion`. The
updater gets the download from `releases/download/v<version>/`, with the version from
the manifest. A different version gives an update prompt whose download fails (§10).

**Integer code.** Google Play and the App Store need an integer that always increases:

```
code = (year mod 100) × 100000 + week × 1000 + count
```

- `26.29.1` gives `26·100000 + 29·1000 + 1 = 2629001`.
- The year term is the largest term. Thus week 1 of a new year has a higher code than
  week 52 of the old year: `27.1.1` gives `2701001`, which is more than `26.52.9`.
- The week (≤ 53) and the count (≤ 999) fit in the `×100000` step. The Play limit is
  `2100000000`, so the codes stay in range.
- The code wraps in 2100, because `00.x.y` is less than `99.x.y`.
- The code ignores the `-dev` suffix. A dev build before the first tag (`0.0.0-dev`)
  gets the code 1, because Android needs a `versionCode` of 1 or more.

Three implementations calculate this code. Keep them the same:
`projectVersionCode()` in `composite/src/main/kotlin/com/snapshotpb/composite/util/Version.kt`,
`version_code()` in `scripts/cut_version.py`, and `versionCode()` in
`app/zepp/scripts/stamp-version.mjs`.

**iOS build number.** App Store Connect accepts an upload only when its build number is
more than the number of the last upload for the same marketing version. The rule is
"one tag = one build number". The iOS job uses
`CURRENT_PROJECT_VERSION = code + github.run_attempt − 1`. Attempt 1 uses the code
without a change. A second run of the job for the same tag uses the next integer, so it
does not send a build number again.

**Week number.** Use the ISO-8601 week with the ISO week-year (`date +%G-%V`, Python
`date.isocalendar()`). Do not use `%Y` with `%V`. The two years are different near the
new year, and the mix gives a lower version after a higher one. For example, 2025-12-29
is week 1 of the ISO week-year 2026. With `%Y`, that day gives `25.1` after `25.52`.

**Count from the tags.** The count is the highest count of the tags `v<year>.<week>.*`,
plus 1. When there is no tag for that week, the count is 1. `cut_version.py` ignores a
tag that does not have the form `v<int>.<int>.<int>`. Nothing in the repository stores
a counter.

## 4. Version source in the build

There is no committed version file. One resolver calculates the version, and every
module uses it. Thus all targets always show the same version.

`resolveProjectVersion()` in the composite build (`Version.kt`) runs
`git describe --tags --always --dirty` one time, through a Gradle `ValueSource`. The
`ValueSource` keeps the configuration cache valid. The root `build.gradle.kts` sets the
result on `allprojects`. `parseGitDescribe()` changes the `git describe` output as
follows:

| `git describe` output | Version | Build type |
|---|---|---|
| `v26.29.1` (on a tag, clean tree) | `26.29.1` | Release |
| `v26.29.1-7-gabc123` | `26.29.1-dev+7-gabc123` | Dev |
| `v26.29.1-dirty` | `26.29.1-dev+dirty` | Dev |
| `abc1234` (no tag) | `0.0.0-dev+gabc1234` | Dev |
| No output (no git) | `0.0.0-dev` | Dev |

A clean release version has no `-dev` marker (§0, goal 5). The part after `+` is SemVer
build metadata. The resolver changes a `+` in that part to a `.`.

**Where the version goes:**

- **Desktop.** `packageVersion = packagingVersion(project.version)`. This function
  removes all text from the first `-`, so `26.29.1-dev+7-gabc123` becomes `26.29.1`.
  The function is necessary: jpackage rejects a suffix at configuration time (§3).
- **Android and Wear.** `versionName` is the full version, with the marker.
  `:app:android` uses `versionCode = projectVersionCode(version)` and `:app:wear` uses
  `versionCode = wearVersionCode(version)` (`100_000_000 + projectVersionCode(version)`)
  so the phone and watch AABs do not collide in the shared `com.snapshotpb.configuration`
  Play listing (§3, §6.2).
- **iOS.** The release job gives `MARKETING_VERSION` and `CURRENT_PROJECT_VERSION` to
  `xcodebuild` as command-line build settings (§6.3). A command-line build setting
  overrides `iosApp/Configuration/Config.xcconfig`. The job does not edit that file. An
  edit makes `git describe --dirty` report a dirty tree, and the Gradle phase of the
  Xcode build then stamps a `-dev+dirty` version. The file holds the placeholders
  `1.0` and `1` for local builds.
- **In-app version.** `core:app-version` generates `AppVersion` with `NAME` and `CODE`.
  `NAME` does not include the build metadata after `+`, so it stays the same between
  commits (`26.30.1-dev`). The `-dev` marker still shows a dev build. To include the
  metadata, build with `-Psnapshotpb.stampBuildMetadata=true`. `DeviceBrowserScreen`
  shows `AppVersion.NAME`. That screen is the first screen on every platform, so no
  platform needs its own About screen.
- **Zepp.** `app/zepp/scripts/stamp-version.mjs` applies the same rules to `app.json`.

**Provenance.** A local build on a clean checkout of a tag also gets the clean version.
A dev build after a tag also gets the same `packageVersion` as that tag. Thus the version
alone does not show which workflow made an artifact. A release is an artifact that the
build workflow published from a clean checkout of a tag.

The root build gets `composite` as a pre-published jar from `composite/m2`. After a
change to `Version.kt`, republish that jar. `CLAUDE.md` gives the procedure.

## 5. Release flow

A release uses two workflows. `release.yml` calculates the version and pushes the tag.
`build-release.yml` builds that tag and publishes it.

```
operator               release.yml (workflow_dispatch)          build-release.yml (workflow_dispatch)
────────               ───────────────────────────────          ─────────────────────────────────────
Run "Release"  ──────▶ refuse a ref that is not on master
 (Actions tab)         run the scripts/tests unit tests
                       cut_version.py --tag --ref HEAD
                       ├─ fetch tags → next v26.29.N
                       ├─ git tag -a v26.29.N
                       ├─ git push origin v26.29.N (GITHUB_TOKEN: no push event)
                       └─ gh workflow run build-release.yml --ref v26.29.N ──▶ preflight
                                                                              ├─ refuse a tag not on master
                                                                              ├─ version + code from the tag
                                                                              ├─ probe the secrets (§8)
                                                                              ├─ desktop ×3, android, browser, zepp
                                                                              ├─ create-release (GitHub Release)
                                                                              ├─ publish-play   (gated, §6.2)
                                                                              └─ publish-ios    (gated, §6.3)
```

**`release.yml`:**

- The trigger is `workflow_dispatch`. The optional `ref` input names the commit to
  release. Without the input, the workflow uses its own ref.
- The job stops when the commit is not `master` or an ancestor of `master`. A release
  contains only reviewed code.
- The `release-cut` concurrency group prevents two cuts at the same time. Two cuts at
  the same time can calculate the same count.
- The job pushes the tag with the usual `GITHUB_TOKEN`. GitHub does not send a `push`
  event for a push with that token. Thus the job then starts the build explicitly with
  `gh workflow run`.
- The job publishes nothing and reads no store credentials.

**`build-release.yml`:**

- `workflow_dispatch` is the usual trigger. The optional `tag` input names the tag.
  Without the input, the workflow uses the ref that the dispatch named.
- `push: tags: ['v*']` is only a safety net, for a tag that someone pushed with a
  personal access token (PAT).
- The preflight job stops when the ref is not `v<int>.<int>.<int>`, or when the tag is
  not on `master`.
- The concurrency group is one group for each tag, and a new run does not cancel a run
  in progress.
- `workflow_dispatch` works only for a workflow file on the default branch.

Do not push the tag with a PAT while `release.yml` also starts the build. That causes
both `push` and `workflow_dispatch`, and the tag builds two times. If a tag does not
build, run `build-release.yml` from the Actions tab with the `tag` input.

To remove a bad release, delete the tag and the Release.

To see the next version without a change, run `scripts/cut_version.py --dry-run`
locally. `--print` writes only the next version.

`validation.yml` (pull requests and pushes to `master`) is the dev path. It never makes
a tag and never publishes.

## 6. Per-target build and publish

Each build job checks out the tag with `fetch-depth: 0`, because `git describe` needs
the tag (§4). The jobs are:

| Job | Needs | Gate | Output |
|---|---|---|---|
| `preflight` | — | — | Tag, version, code, and one flag for each secret group (§8) |
| `desktop` (macOS, Windows, Linux) | `preflight` | — | Installers and `latest*.yml` (§6.1) |
| `android` | `preflight` | — | AAB and APK for phone and Wear (§6.2) |
| `browser` | `preflight` | — | Wasm bundle as a tarball (§6.4) |
| `zepp` | `preflight` | — | Zepp OS `.zab` package (§6.5) |
| `create-release` | `preflight`, `desktop`, `android`, `browser`, `zepp` | — | The GitHub Release |
| `publish-play` | `preflight`, `android` | `has_play` and `has_android_signing` | Google Play internal track (§6.2) |
| `publish-ios` | `preflight` | `has_ios` | App Store Connect (§6.3) |

`create-release` downloads all build artifacts into one flat directory. If two files
have the same name, the job stops, because one file would replace the other. The job
then creates the Release with `softprops/action-gh-release`: `draft: false`,
`prerelease: false`, `make_latest: true`, `generate_release_notes: true` and
`fail_on_unmatched_files: true`. The Release must be a full release, because the updater
reads only the latest full release (§10).

A GitHub Release is available immediately. Play and the App Store add a review and a
rollout, so each channel gets the same version at a different time.

### 6.1 Desktop and the Nucleus update channel

Three runners (`macos-latest`, `windows-latest`, `ubuntu-latest`) each run
`:app:desktop:packageDistributionForCurrentOS -Psnapshotpb.enableAutoUpdater=true`.
The property sets `DesktopConfig.ENABLE_AUTO_UPDATER` to `true`. Dev and PR builds keep
it `false`. Each runner uploads the installers **and** the update manifest:

| OS | Installers | Update manifest | Notes |
|---|---|---|---|
| macOS | `.dmg` and `.zip` | `latest-mac.yml` (merged) | The `.zip` is necessary (see below). Signing and notarization per §7. |
| Windows | `.msi` | `latest.yml` | Authenticode per §7 |
| Linux | `.AppImage` | `latest-linux.yml` | AppImage, not Flatpak (§10) |

**Use `packageDistributionForCurrentOS`.** Do not use
`packageReleaseDistributionForCurrentOS`. That task runs ProGuard, which has no
configuration here and removes classes that Kodein and kotlinx.serialization find by
reflection.

**The `.zip` rule for macOS.** The Nucleus updater can install and restart silently on
macOS only from a `.zip`. For a `.dmg`, it can only open the file and stop. The user
must then move the app by hand, and `installAndRestart()` does not restart.
`app/desktop/build.gradle.kts` adds `TargetFormat.Zip` only when the build runs on
macOS. `Zip` has no `targetOS`, so without this condition every runner makes a zip. A
zip on Windows or Linux adds a second `files:` entry that the updater can select before
the real installer.

**Merged macOS manifests.** Nucleus runs electron-builder one time for each target
format, each into its own directory (`build/compose/binaries/main/<format>/`). The
`Dmg` and `Zip` formats thus make two `latest-mac.yml` files. Each file lists only its
own installer. A plain copy into one directory makes one file replace the other, and
the Release then shows only one installer. `scripts/stage_release_manifest.py` merges
manifests that have the same name into one manifest per OS:

- The merged `files:` list has every installer for that OS. The `.zip` is first.
- The script calculates `sha512` and `size` again from the staged files. Thus a change
  to a file after the package step, for example a notarization staple, cannot make the
  manifest wrong.
- The script stops with an error when it finds no manifest. A Release without a manifest
  looks correct but can never update.

**The `publish { github { } }` block makes the manifests.** Without it, the plugin makes
a manifest for `Msi` and `Portable` only, so macOS and Linux cannot update. `publishMode`
stays at its default `Never`: the block controls only the manifest generation. The
upload is the job of `softprops/action-gh-release`, so the build needs no token. The
`owner` and `repo` values must be equal to the values of the `GitHubProvider` in
`app/desktop/src/jvmMain/kotlin/com/snapshotpb/configuration/Application.kt`.

**The result.** A user of `26.28.4` starts the app. The updater reads
`/releases/latest`, finds `26.29.1` in `latest-<os>.yml`, and shows
`UpdatePromptDialog`. The user selects "Update Now". The updater downloads the
installer, checks its `sha512`, and runs `installAndRestart()`.

### 6.2 Android and Wear: Google Play

- The `android` job runs `bundleRelease` and `assembleRelease` for `:app:android` and
  `:app:wear`. The version comes from §4.
- The release builds are signed when the upload keystore and its secrets exist (§7).
  Without them, the builds are unsigned.
- The job attaches the AABs and the APKs to the GitHub Release. The APKs are for
  sideload and QA, because a device cannot install an `.aab` directly.
- `publish-play` authenticates to Google Cloud through Workload Identity Federation
  (`google-github-actions/auth`, service account
  `play-publisher@snapshotpb-play-store.iam.gserviceaccount.com`) and uploads to the
  `internal` track with `r0adkll/upload-google-play`. It runs only when both `has_play`
  (`PLAY_ENABLED`) and `has_android_signing` are true.
- The phone and Wear apps share the `com.snapshotpb.configuration` `applicationId` and
  Google Play listing so Google Play Services' Wearable Data Layer delivers sync events
  between them. `:app:android` uses `projectVersionCode` (`internal` track) and
  `:app:wear` uses `wearVersionCode` (`100_000_000 + projectVersionCode`, `wear:internal`
  track) so the two AABs do not collide on `versionCode`. Each upload step names its own
  file (`dist/android-release.aab`, `dist/wear-release.aab`) and track explicitly.
- The Wear upload has its own gate, `WEAR_PLAY_ENABLED`. Play rejects an upload to
  `wear:internal` before the Wear OS form factor is enabled in Play Console. That failure
  would also make the run fail after a good phone upload.

### 6.3 iOS: App Store Connect

- `publish-ios` runs on `macos-latest` and only when `has_ios` is true (§8).
- `xcodebuild archive` gets `MARKETING_VERSION` and `CURRENT_PROJECT_VERSION` (§3) as
  command-line build settings. `Config.xcconfig` stays unchanged (§4).
- Signing is automatic. The job gives an App Store Connect API key to `xcodebuild` with
  `-allowProvisioningUpdates`, so Xcode gets the distribution profile itself. No
  provisioning profile is a secret. This matches `CODE_SIGN_STYLE = Automatic` in the
  project, so local builds and CI use the same method.
- The job sets `CODE_SIGN_IDENTITY="Apple Distribution"` on the command line. The
  project sets `"Apple Development"` in its Release configuration for on-device debug,
  and App Store Connect rejects an app with a development signature. The override on the
  command line keeps the project file usable for local work.
- `iosApp.xcscheme` must stay shared (under `xcshareddata`). CI runs
  `xcodebuild -scheme iosApp`, and a scheme that is not shared exists only on one
  computer. The scheme archives the Release configuration.
- `iosApp/ExportOptions.plist` sets `method = app-store-connect` and
  `manageAppVersionAndBuildNumber = false`. With the default value, Xcode writes a new
  `CFBundleVersion` during the export and discards the build number from the tag.
- The bundle identifier is the literal `com.snapshotpb.configuration.SnapshotPB`. It
  does not use `$(TEAM_ID)`, because the bundle identifier is the permanent App Store
  identity of the app.
- `iosApp/ExportOptions.plist` sets `destination = upload`. Thus
  `xcodebuild -exportArchive` sends the build to App Store Connect with the same API
  key, and the job makes no `.ipa`. The job stops before the archive when the plist does
  not set `upload`. With `export`, xcodebuild writes an `.ipa` file, sends nothing, and
  reports success.
- The runner needs the Android SDK and the Gradle setup. The "Compile Kotlin Framework"
  phase of the Xcode target runs `./gradlew :app:ios:embedAndSignAppleFrameworkForXcode`,
  and Gradle configures all modules, also the Android modules. There is no separate
  `:app:ios:build` step. The archive runs that phase first, so a Kotlin error shows
  just as early.

### 6.4 Browser

The `browser` job runs `:app:browser:wasmJsBrowserDistribution` and attaches the bundle
to the Release as `snapshotpb-web-<version>.tar.gz`. The workflow does not deploy it.
The bundle is not part of the Nucleus update channel.

### 6.5 Zepp OS

- The `zepp` job runs `npm ci` and `npm run build` in `app/zepp` with Node.js 22 and
  `CI=true`. The build runs `stamp-version.mjs`, which writes the tag version and code
  into `app.json` (§4). For `v26.29.1`, the package gets the name `26.29.1` and the code
  `2629001`.
- The job attaches the package to the Release as `snapshotpb-zepp-<version>.zab`. The
  package is not signed, and the job does not upload it to the Zepp store. The owner
  decides on these steps.
- The job reads the version of each `app.json` and of `manifest.json` in the `.zab`. It
  stops when a value is not the tag version. A dirty tree gives a `-dev` version, so this
  check also stops a release when the build changed a tracked file.
- The build writes `src/protocol/proto.js` again before it stamps the version. The
  `build-proto` script puts the `.proto` globs in quotes, so `pbjs` expands them itself
  and sorts them the same way on each computer. A shell sorts a glob by its locale:
  `dash` on Linux puts `device.proto` before `device_config.proto`, but the committed
  file has the other order. Without the quotes, the Linux runner writes a different
  `proto.js`, and the tree becomes dirty.

## 7. Signing and notarization

Each signing step runs only when its secrets exist (§8). Without the secrets, the
artifact ships unsigned. Local, PR and dev builds never sign.

- **Windows (desktop).** `app/desktop/build.gradle.kts` signs the MSI when
  `MSI_SIGNING_STORE_FILE` names a `.pfx` file, with the password
  `MSI_SIGNING_STORE_PASS`. The Windows job decodes the `MSI_SIGNING_PFX_BASE64` secret
  into a file in `$RUNNER_TEMP` and sets `MSI_SIGNING_STORE_FILE`. An unsigned MSI
  causes a SmartScreen warning.
- **macOS (desktop).** The Nucleus plugin reads signing and notarization only from
  Gradle properties: `compose.desktop.mac.sign`, `.signing.identity`,
  `.signing.keychain` and `.notarization.{appleID,password,teamID}`. It has no
  environment-variable fallback. The job imports the Developer ID certificate into a
  temporary keychain and adds the properties to the `gradle.properties` of the runner.
  It does not use `-P` flags, because a flag puts the notary password in the process
  list. Do not add a `signing {}` or `notarization {}` block to the build script, because
  that block overrides the properties.
- **Sign during the package step, not after it.** Notarization staples a ticket into the
  `.dmg` and the `.zip`, which changes their bytes. A signature after the package step
  makes `latest-mac.yml` describe the old bytes, and the updater then rejects every
  download (§10). The stage script also calculates the hashes again (§6.1). The two
  protections are independent, and both are necessary.
- **Unsigned macOS builds.** Without the certificate, the user must right-click and
  select Open to get past Gatekeeper.
- **Linux (desktop).** The AppImage is unsigned. The updater checks the `sha512` from
  `latest-linux.yml` (§10).
- **Android and Wear.** The release `signingConfig` in `app/android` and `app/wear`
  exists only when `ANDROID_KEYSTORE_FILE` is set. The upload keystore is in the
  repository as the GPG-encrypted file `signing/upload.keystore.gpg` (symmetric, AES256).
  Before the build, the `android` job decrypts it into that file with
  `ANDROID_KEYSTORE_GPG_PASSPHRASE`. Both apps use the same upload key, and Play
  App Signing holds the app key. Play accepts only a signed upload, so the Play upload
  has the same gate (§6.2).
- **The upload keystore.** It is a PKCS12 keystore with the alias `upload` and an RSA
  4096 key. PKCS12 has one password for the store and the key, so
  `ANDROID_KEYSTORE_PASSWORD` and `ANDROID_KEY_PASSWORD` have the same value. The
  certificate ends on 9999-12-31, which is the maximum that X.509 permits. `.gitignore`
  keeps all other files of `signing/` out of Git, so the decrypted keystore and its
  passwords can be there on a local disk. To replace the keystore, encrypt the new file
  with `gpg --symmetric --cipher-algo AES256 --output signing/upload.keystore.gpg signing/upload.keystore`.
- **iOS.** App Store Connect needs the Apple distribution certificate and the API key
  (§6.3). The upload job has a gate on these secrets.

**The `release` environment.** Every job that reads a signing or publish secret declares
`environment: release`. A repository secret is available to every workflow on every
branch. An environment secret is available only to a job in that environment. A job in
an environment can also read repository secrets. The environment has no required
reviewers while the repository is private, so the jobs run with no approval step. Only
users with permission to run Actions in this repository can start a release.

**To do before the repository becomes public:** give the `release` environment required
reviewers, so that a maintainer approves each release job.

## 8. Secrets

Each group of secrets has its own gate, so each group enables only its own part of the
release. Keep these secrets in the `release` environment, not in the repository secrets.
The Android signing secrets are different: they are organization secrets, so that other
Android projects of the organization can sign with the same upload key. Their visibility
is the private repositories of the organization, and each workflow of such a repository
can read them.

| Group | Secrets | Gate (preflight output) | Without the secrets |
|---|---|---|---|
| Play upload | `PLAY_ENABLED` (a flag, not a credential; auth uses Workload Identity Federation) | `has_play` | No Play upload. Set it after the phone listing exists and `play-publisher@snapshotpb-play-store.iam.gserviceaccount.com` has release permissions in Play Console. |
| Android signing (organization secrets) | `ANDROID_KEYSTORE_GPG_PASSPHRASE`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD` | `has_android_signing` (also needs `signing/upload.keystore.gpg` in the tag) | Unsigned AAB and APK, no Play upload |
| Wear on Play | `WEAR_PLAY_ENABLED` (a flag, not a credential) | `has_wear_play` | No Wear upload. Set it only after the Wear OS form factor is enabled on `com.snapshotpb.configuration` in Play Console. |
| iOS | `IOS_CERTIFICATE_BASE64`, `IOS_CERTIFICATE_PASSWORD`, `IOS_KEYCHAIN_PASSWORD`, `APP_STORE_CONNECT_API_KEY_BASE64`, `APP_STORE_CONNECT_API_KEY_ID`, `APP_STORE_CONNECT_API_ISSUER_ID` | `has_ios` | The iOS job does not start |
| macOS signing | `MACOS_CERTIFICATE_BASE64`, `MACOS_CERTIFICATE_PASSWORD`, `MACOS_SIGNING_IDENTITY`, `MACOS_KEYCHAIN_PASSWORD` | `has_macos_signing` | Unsigned `.dmg` and `.zip` |
| macOS notarization | `MACOS_NOTARY_APPLE_ID`, `MACOS_NOTARY_PASSWORD`, `MACOS_NOTARY_TEAM_ID` | `has_macos_notarization` (used only with signing) | Signed but not notarized |
| Windows signing | `MSI_SIGNING_PFX_BASE64` (the `.pfx` file in base64), `MSI_SIGNING_STORE_PASS` | `has_msi_signing` | Unsigned MSI |

The `has_ios` gate needs the certificate, its password and all three API-key values.
Thus the iOS job has all its credentials, or it does not start. It does not fail late
in a long macOS archive. `MSI_SIGNING_STORE_FILE` is not a secret: the Windows job sets
it (§7).

## 10. Nucleus updater contract

This section describes the behavior of `nucleus.updater-runtime` 1.15.7
(`gradle/libs.versions.toml`). No published document gives these facts. They come from
the decompiled runtime jar, and they set exactly what the release must produce.

- **Version parse: strict SemVer.** `Version(major, minor, patch, meta)` uses a
  `SEMVER_REGEXP`, and `compareTo` uses the standard SemVer order. `26.29.1` gives
  `major=26, minor=29, patch=1`, and the calendar versions increase in the correct
  order. A `DEV_VERSION` sentinel and `isDevMode`, `allowPrerelease` and `channel`
  control dev and prerelease versions.
- **Update manifest: electron-builder YAML.** Each `YamlFileEntry` has `url`, `sha512`,
  `size` and `blockMapSize`. These are the `files:` entries of `latest.yml`,
  `latest-mac.yml` and `latest-linux.yml`. The Release must include the manifest for each
  OS as an asset (§6.1). The Nucleus package task makes the manifest from
  `packageVersion`.
- **`GitHubProvider`.** It sends requests to `https://api.github.com` with
  `Accept: application/vnd.github+json`. It gets the **latest** release
  (`/repos/{owner}/{repo}/releases/latest`), which is never a prerelease or a draft
  (§6). It then gets the manifest for the platform and the installer. The app uses
  `GitHubProvider(owner = "SnapshotPB", repo = "Configuration-App")`.
- **The download URL comes from the manifest version.** `NucleusUpdater` calls
  `provider.getDownloadUrl(entry.url, yamlMetadata.version)`, and `GitHubProvider`
  makes `https://github.com/{owner}/{repo}/releases/download/v{version}/{fileName}`.
  Thus the Release tag **must** be exactly `v` plus the version in `latest*.yml`. For
  this reason, a version override for one format is not permitted (§3).
- **Silent install only from a macOS `.zip`.** `PlatformInstaller.install` can install
  and restart on macOS only from a `.zip`. For a `.dmg`, it opens the file and stops, so
  `installAndRestart()` does not restart. Thus macOS ships both files (§6.1).
- **File selection.** `FileSelector` selects a file by its extension and CPU
  architecture (`X64_PATTERNS`, `ARM64_PATTERNS`). It recognizes `.exe` and `.msi`
  (Windows), `.dmg` and `.zip` (macOS), and `.deb`, `.rpm`, `.appimage` and `.snap`
  (Linux). **It does not recognize `.flatpak`** (§6.1).
- **Integrity.** `ChecksumVerifier` compares each download with the `sha512` in the
  manifest. A difference causes a `ChecksumException`.
- **Configuration** (`UpdaterConfig`): `currentVersion` (the default comes from
  `packageVersion`), `provider`, `channel`, `allowDowngrade`, `allowPrerelease`,
  `executableType` and a custom `httpClient`.
