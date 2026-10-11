#!/usr/bin/env python3
"""Stage desktop installers + a single, correct update manifest per OS.

The Nucleus in-app updater reads an electron-builder-style ``latest*.yml`` from the
GitHub Release to discover the new version, the matching installer URL, and its
``sha512`` (docs/versioning-and-release-plan.md §10). Producing that manifest correctly
is the whole point of the desktop release leg, so this script owns two things the naive
"copy everything into one directory" approach gets wrong:

**1. One manifest per OS, not per packaging format.**
Nucleus drives electron-builder once *per target format*, each into its own output
directory (``build/compose/binaries/main/<format>/``). On macOS we ship both ``Dmg`` and
``Zip`` (the zip is the only format the updater can silently install-and-restart from),
so **two** ``latest-mac.yml`` files are produced — one per directory, each listing only
its own installer. Flattening them into a single upload directory makes one silently
overwrite the other, and the Release then advertises only one of the two installers.
Whichever loses is invisible to the updater. This script merges same-named manifests
into one whose ``files:`` lists every installer for that OS.

**2. Hashes that match the bytes actually uploaded.**
``sha512``/``size`` are recomputed from the staged files rather than trusted from the
generated manifest, so any post-processing between packaging and upload (macOS
codesign + notarization + staple being the live example — stapling rewrites the file)
cannot desync the manifest from the artifact. A mismatch is not cosmetic: the updater
verifies the download against the manifest and raises ``ChecksumException``.

Usage::

    stage_release_manifest.py --build-dir app/desktop/build --dist-dir dist

Exits non-zero if no manifest is found — a Release without one looks healthy but can
never be updated from, which is precisely the failure this leg exists to prevent.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import os
import shutil
import sys

# Installer extensions the Nucleus FileSelector recognizes (plan §10). Used to order
# `files:` entries; anything else keeps its relative order after these.
#
# `.zip` sorts FIRST on purpose: on macOS it is the only format Nucleus can silently
# install and restart from (a `.dmg` falls through to `open`-ing the file and exiting).
# electron-builder applies the same convention, and the updater's own file selection
# should never have to depend on which packaging task happened to finish first.
EXTENSION_PRIORITY = (".zip", ".dmg", ".msi", ".exe", ".appimage", ".deb", ".rpm", ".snap")

# Keys we understand at the top level of a manifest. Anything else is preserved verbatim
# so a future electron-builder field is not silently dropped on the floor.
FILES_KEY = "files"


def sha512_base64(path: str) -> str:
    """electron-builder's checksum encoding: base64 of the raw SHA-512 digest."""
    digest = hashlib.sha512()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return base64.b64encode(digest.digest()).decode("ascii")


def _scalar(raw: str) -> "str | int":
    """Parse a manifest scalar: quoted string, bare int, or bare string."""
    value = raw.strip()
    if len(value) >= 2 and value[0] == value[-1] and value[0] in ("'", '"'):
        return value[1:-1]
    if value.isdigit():
        return int(value)
    return value


def _emit_scalar(value: "str | int") -> str:
    """Render a scalar, quoting only when a bare value would be ambiguous YAML.

    Filenames can contain spaces (``packageName`` is "Snapshot PB"), and a timestamp
    like ``2026-07-23T21:57:44.035Z`` must stay a string rather than parse as a date.
    """
    if isinstance(value, int):
        return str(value)
    text = str(value)
    needs_quotes = (
        text == ""
        or text[0] in "'\"[]{}>|*&!%@`#-?:,"
        or ": " in text
        or text.strip() != text
        or any(char in text for char in ":#")
    )
    return "'" + text.replace("'", "''") + "'" if needs_quotes else text


def parse_manifest(text: str) -> dict:
    """Parse the narrow YAML subset electron-builder emits for ``latest*.yml``.

    Hand-rolled rather than pulling in PyYAML: the schema is tiny, fixed, and machine
    generated, and this runs on three GitHub runners where the system Python has no
    third-party packages. Anything outside the expected shape raises rather than being
    silently reinterpreted.
    """
    data: dict = {}
    files: "list[dict]" = []
    in_files = False
    current: "dict | None" = None

    for lineno, raw in enumerate(text.splitlines(), start=1):
        if not raw.strip() or raw.lstrip().startswith("#"):
            continue
        indent = len(raw) - len(raw.lstrip())
        line = raw.strip()

        if indent == 0:
            in_files = line == f"{FILES_KEY}:"
            current = None
            if in_files:
                continue
            if ":" not in line:
                raise ValueError(f"line {lineno}: expected 'key: value', got {line!r}")
            key, _, value = line.partition(":")
            data[key.strip()] = _scalar(value)
            continue

        if not in_files:
            raise ValueError(f"line {lineno}: unexpected indented line outside 'files:': {line!r}")

        if line.startswith("- "):
            current = {}
            files.append(current)
            line = line[2:].strip()
        if current is None:
            raise ValueError(f"line {lineno}: file entry field before any '- ' item: {line!r}")
        if ":" not in line:
            raise ValueError(f"line {lineno}: expected 'key: value' in file entry, got {line!r}")
        key, _, value = line.partition(":")
        current[key.strip()] = _scalar(value)

    data[FILES_KEY] = files
    return data


def render_manifest(data: dict) -> str:
    """Render back to the electron-builder layout: version, files, path, sha512, ..."""
    files = data.get(FILES_KEY, [])
    lines: "list[str]" = []

    if "version" in data:
        lines.append(f"version: {_emit_scalar(data['version'])}")

    lines.append(f"{FILES_KEY}:")
    for entry in files:
        first = True
        for key, value in entry.items():
            prefix = "  - " if first else "    "
            lines.append(f"{prefix}{key}: {_emit_scalar(value)}")
            first = False

    for key, value in data.items():
        if key in ("version", FILES_KEY):
            continue
        lines.append(f"{key}: {_emit_scalar(value)}")

    return "\n".join(lines) + "\n"


def _priority(url: str) -> int:
    lowered = url.lower()
    for index, extension in enumerate(EXTENSION_PRIORITY):
        if lowered.endswith(extension):
            return index
    return len(EXTENSION_PRIORITY)


def merge_manifests(manifests: "list[dict]") -> dict:
    """Union the ``files:`` of same-OS manifests into one, installer-priority ordered.

    All inputs must agree on ``version`` — they are built from the same tag in the same
    run, so a disagreement means two different builds got mixed into one Release and the
    updater would advertise a version some assets do not correspond to.
    """
    if not manifests:
        raise ValueError("no manifests to merge")

    versions = {str(m.get("version")) for m in manifests if "version" in m}
    if len(versions) > 1:
        raise ValueError(f"manifests disagree on version: {sorted(versions)}")

    merged: dict = {}
    for manifest in manifests:
        for key, value in manifest.items():
            if key != FILES_KEY and key not in merged:
                merged[key] = value

    entries: "list[dict]" = []
    seen: "set[str]" = set()
    for manifest in manifests:
        for entry in manifest.get(FILES_KEY, []):
            url = str(entry.get("url", ""))
            if not url or url in seen:
                continue
            seen.add(url)
            entries.append(dict(entry))

    # Stable sort: installer priority first, original discovery order within a tier, so
    # the result does not depend on filesystem walk order across runners.
    entries.sort(key=lambda entry: _priority(str(entry.get("url", ""))))
    merged[FILES_KEY] = entries
    return merged


def refresh_entries(manifest: dict, dist_dir: str) -> dict:
    """Recompute ``sha512``/``size`` for every entry from the staged file.

    ``blockMapSize`` describes the differential-download block map baked into the
    artifact. If the bytes changed after packaging (notarization/stapling) the recorded
    size no longer describes them, so it is dropped rather than left stale — that only
    disables differential download, whereas a wrong value would describe the file
    incorrectly. It is kept when the hash proves the file is byte-identical.
    """
    refreshed = dict(manifest)
    entries = []
    for entry in manifest.get(FILES_KEY, []):
        url = str(entry["url"])
        path = os.path.join(dist_dir, url)
        if not os.path.isfile(path):
            raise SystemExit(f"manifest references '{url}' but it was not staged into {dist_dir}")
        updated = dict(entry)
        actual_sha = sha512_base64(path)
        unchanged = updated.get("sha512") == actual_sha
        updated["sha512"] = actual_sha
        updated["size"] = os.path.getsize(path)
        if "blockMapSize" in updated and not unchanged:
            blockmap = path + ".blockmap"
            if os.path.isfile(blockmap):
                updated["blockMapSize"] = os.path.getsize(blockmap)
            else:
                del updated["blockMapSize"]
        entries.append(updated)
    refreshed[FILES_KEY] = entries

    # Top-level path/sha512 mirror the primary (first) entry — the one the updater
    # falls back to when it does not pick by extension.
    if entries:
        refreshed["path"] = entries[0]["url"]
        refreshed["sha512"] = entries[0]["sha512"]
    return refreshed


def find_manifests(build_dir: str) -> "list[str]":
    found = []
    for root, _dirs, filenames in os.walk(build_dir):
        for name in filenames:
            lowered = name.lower()
            if lowered.startswith("latest") and lowered.endswith(".yml"):
                found.append(os.path.join(root, name))
    return sorted(found)


def stage(build_dir: str, dist_dir: str) -> "list[str]":
    """Copy installers + write one merged manifest per OS. Returns manifest basenames."""
    manifest_paths = find_manifests(build_dir)
    if not manifest_paths:
        raise SystemExit(
            f"No latest*.yml found under {build_dir}.\n"
            "The Nucleus updater needs the per-OS manifest; check that the "
            "nativeDistributions publish { github { } } block is still present."
        )

    os.makedirs(dist_dir, exist_ok=True)

    groups: "dict[str, list[dict]]" = {}
    for path in manifest_paths:
        with open(path, "r", encoding="utf-8") as handle:
            manifest = parse_manifest(handle.read())
        source_dir = os.path.dirname(path)

        for entry in manifest.get(FILES_KEY, []):
            url = str(entry["url"])
            source = os.path.join(source_dir, url)
            if not os.path.isfile(source):
                raise SystemExit(f"{path} references '{url}', which is missing from {source_dir}")
            shutil.copy2(source, os.path.join(dist_dir, url))
            blockmap = source + ".blockmap"
            if os.path.isfile(blockmap):
                shutil.copy2(blockmap, os.path.join(dist_dir, url + ".blockmap"))

        groups.setdefault(os.path.basename(path).lower(), []).append(manifest)

    written = []
    for name, manifests in sorted(groups.items()):
        merged = refresh_entries(merge_manifests(manifests), dist_dir)
        with open(os.path.join(dist_dir, name), "w", encoding="utf-8") as handle:
            handle.write(render_manifest(merged))
        listed = ", ".join(str(entry["url"]) for entry in merged.get(FILES_KEY, []))
        print(f"{name}: {len(manifests)} source manifest(s) -> [{listed}]")
        written.append(name)
    return written


def main(argv: "list[str] | None" = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--build-dir", required=True, help="desktop module build dir to scan for latest*.yml")
    parser.add_argument("--dist-dir", required=True, help="directory to stage installers + manifests into")
    args = parser.parse_args(argv)
    stage(args.build_dir, args.dist_dir)
    return 0


if __name__ == "__main__":
    sys.exit(main())
