#!/usr/bin/env node
/**
 * Stamps the calendar-derived project version into the Zepp app's `app.json`.
 *
 * The Zepp app is not a Gradle module, so it never saw the version resolved by
 * `composite/src/main/kotlin/com/snapshotpb/composite/util/Version.kt` for every other target —
 * its `app.json` sat pinned at `1.0.0` / code `1` while Android, Wear, desktop and iOS all moved
 * with the tags (docs/versioning-and-release-plan.md).
 *
 * This resolves the same `git describe` output through the same rules, so a `.zab` built from a
 * tag reports the same version as the APK cut from it:
 *
 *   - on a tag, clean tree: `v26.29.1`          -> name `26.29.1`,     code 2629001
 *   - dev/PR/master:        `v26.29.1-7-gabc1`  -> name `26.29.1-dev`, code 2629001
 *   - before the first tag: `gabc1234`          -> name `0.0.0-dev`,   code 1
 *
 * Writes only with `--write` (or when `CI` is set, which every GitHub Actions runner exports), so a
 * local `npm run build` reports the version without leaving `app.json` dirty in the working tree.
 * Keep the parsing in step with `parseGitDescribe`/`projectVersionCode` in the composite build and
 * `version_code()` in `scripts/cut_version.py`.
 */
import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const appJsonPath = resolve(here, '..', 'app.json');
const repoRoot = resolve(here, '..', '..', '..');

/** Mirrors `parseGitDescribe` in composite build-logic. */
function parseGitDescribe(raw) {
  const describe = (raw || '').trim();
  if (describe === '') return '0.0.0-dev';
  const d = describe.startsWith('v') ? describe.slice(1) : describe;

  if (/^\d+\.\d+\.\d+$/.test(d)) return d;

  const match = /^(\d+\.\d+\.\d+)(?:-(.+))?$/.exec(d);
  if (match) {
    const base = match[1];
    const extra = match[2] || '';
    // SemVer build metadata is dot-separated [0-9A-Za-z-]; normalise any stray '+' to '.'.
    return extra === '' ? `${base}-dev` : `${base}-dev+${extra.replace(/\+/g, '.')}`;
  }

  return `0.0.0-dev+g${d}`;
}

/** Mirrors `projectVersionCode` in composite build-logic and `version_code()` in cut_version.py. */
function versionCode(version) {
  const parts = version.split('-')[0].split('.');
  const component = (index) => {
    const n = Number.parseInt(parts[index], 10);
    return Number.isNaN(n) ? 0 : n;
  };
  const code = (component(0) % 100) * 100000 + component(1) * 1000 + component(2);
  return Math.max(1, code);
}

function gitDescribe() {
  try {
    return execFileSync('git', ['describe', '--tags', '--always', '--dirty'], {
      cwd: repoRoot,
      encoding: 'utf8',
      stdio: ['ignore', 'pipe', 'ignore'],
    });
  } catch {
    // No git, shallow clone without tags, or git not on PATH: fall back to an obviously-dev version
    // rather than failing the build.
    return '';
  }
}

const shouldWrite = process.argv.includes('--write') || Boolean(process.env.CI);

// The in-app surface drops the `+<commits>-g<sha>` build metadata for the same reason
// core:app-version does: it changes on every commit and buys nothing a user can act on.
const fullVersion = parseGitDescribe(gitDescribe());
const versionName = fullVersion.split('+')[0];
const code = versionCode(fullVersion);

const appJson = JSON.parse(readFileSync(appJsonPath, 'utf8'));
const current = appJson.app.version;

if (!shouldWrite) {
  console.log(
    `[stamp-version] resolved ${versionName} (code ${code}); ` +
      `app.json holds ${current.name} (code ${current.code}). ` +
      'Pass --write (or set CI) to stamp it.'
  );
  process.exit(0);
}

if (current.name === versionName && current.code === code) {
  console.log(`[stamp-version] app.json already at ${versionName} (code ${code}).`);
  process.exit(0);
}

appJson.app.version = { code, name: versionName };
writeFileSync(appJsonPath, `${JSON.stringify(appJson, null, 2)}\n`, 'utf8');
console.log(
  `[stamp-version] stamped ${versionName} (code ${code}), was ${current.name} (code ${current.code}).`
);
