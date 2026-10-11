#!/usr/bin/env python3
"""Unit tests for scripts/stage_release_manifest.py.

Run with: python3 -m unittest discover -s scripts/tests

The end-to-end tests build a fake `build/compose/binaries/main/<format>/` tree on disk,
so the macOS two-manifest case is exercised without needing a macOS runner.
"""

import base64
import hashlib
import os
import shutil
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from stage_release_manifest import (  # noqa: E402
    merge_manifests,
    parse_manifest,
    refresh_entries,
    render_manifest,
    sha512_base64,
    stage,
)

# Verbatim output of a real `./gradlew :app:desktop:packageDistributionForCurrentOS`
# run on Linux — the shape this parser has to handle.
LINUX_MANIFEST = """version: 26.30.1
files:
  - url: snapshot-pb-26.30.1-linux-x86_64.AppImage
    sha512: 2+rdHtoubpOyr+cbcenrCihaj5DrkghtR6FbQtlJKMCD1rzMx+BIh6hgFEpj6s4V8W7MUJHOgSOCq/oahDUcOA==
    size: 117572386
    blockMapSize: 123702
path: snapshot-pb-26.30.1-linux-x86_64.AppImage
sha512: 2+rdHtoubpOyr+cbcenrCihaj5DrkghtR6FbQtlJKMCD1rzMx+BIh6hgFEpj6s4V8W7MUJHOgSOCq/oahDUcOA==
releaseDate: '2026-07-23T21:57:44.035Z'
"""


def _manifest_for(url, sha, size, block=None):
    lines = [
        "version: 26.30.1",
        "files:",
        f"  - url: {url}",
        f"    sha512: {sha}",
        f"    size: {size}",
    ]
    if block is not None:
        lines.append(f"    blockMapSize: {block}")
    lines += [f"path: {url}", f"sha512: {sha}", "releaseDate: '2026-07-23T21:57:44.035Z'"]
    return "\n".join(lines) + "\n"


class ParseTests(unittest.TestCase):
    def test_parses_real_manifest(self):
        data = parse_manifest(LINUX_MANIFEST)
        self.assertEqual(data["version"], "26.30.1")
        self.assertEqual(data["releaseDate"], "2026-07-23T21:57:44.035Z")
        self.assertEqual(len(data["files"]), 1)
        entry = data["files"][0]
        self.assertEqual(entry["url"], "snapshot-pb-26.30.1-linux-x86_64.AppImage")
        self.assertEqual(entry["size"], 117572386)
        self.assertEqual(entry["blockMapSize"], 123702)
        self.assertTrue(entry["sha512"].endswith("=="))

    def test_round_trip_is_stable(self):
        once = render_manifest(parse_manifest(LINUX_MANIFEST))
        twice = render_manifest(parse_manifest(once))
        self.assertEqual(once, twice)
        self.assertEqual(parse_manifest(once), parse_manifest(LINUX_MANIFEST))

    def test_release_date_stays_a_string(self):
        # Rendered unquoted it would parse as a timestamp, not the string the updater wants.
        rendered = render_manifest(parse_manifest(LINUX_MANIFEST))
        self.assertIn("releaseDate: '2026-07-23T21:57:44.035Z'", rendered)

    def test_rejects_unexpected_shape(self):
        with self.assertRaises(ValueError):
            parse_manifest("version: 1.0\n  stray: value\n")
        with self.assertRaises(ValueError):
            parse_manifest("this-has-no-colon\n")


class MergeTests(unittest.TestCase):
    def test_merges_mac_dmg_and_zip_into_one(self):
        dmg = parse_manifest(_manifest_for("app-26.30.1.dmg", "AAAA", 10))
        zipped = parse_manifest(_manifest_for("app-26.30.1-mac.zip", "BBBB", 20))
        merged = merge_manifests([dmg, zipped])
        urls = [entry["url"] for entry in merged["files"]]
        # Both survive, and the zip leads: it is the only macOS format the Nucleus
        # updater can silently install-and-restart from.
        self.assertEqual(urls, ["app-26.30.1-mac.zip", "app-26.30.1.dmg"])

    def test_merge_is_order_independent(self):
        dmg = parse_manifest(_manifest_for("app.dmg", "AAAA", 10))
        zipped = parse_manifest(_manifest_for("app-mac.zip", "BBBB", 20))
        self.assertEqual(
            [e["url"] for e in merge_manifests([dmg, zipped])["files"]],
            [e["url"] for e in merge_manifests([zipped, dmg])["files"]],
        )

    def test_deduplicates_identical_urls(self):
        one = parse_manifest(_manifest_for("app.dmg", "AAAA", 10))
        merged = merge_manifests([one, parse_manifest(_manifest_for("app.dmg", "AAAA", 10))])
        self.assertEqual(len(merged["files"]), 1)

    def test_version_disagreement_is_an_error(self):
        a = parse_manifest(_manifest_for("app.dmg", "AAAA", 10))
        b = parse_manifest(_manifest_for("app-mac.zip", "BBBB", 20).replace("26.30.1", "26.30.2"))
        with self.assertRaises(ValueError):
            merge_manifests([a, b])

    def test_single_manifest_passes_through(self):
        merged = merge_manifests([parse_manifest(LINUX_MANIFEST)])
        self.assertEqual(len(merged["files"]), 1)
        self.assertEqual(merged["version"], "26.30.1")


class RefreshTests(unittest.TestCase):
    def setUp(self):
        self.dist = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.dist)

    def _write(self, name, content):
        path = os.path.join(self.dist, name)
        with open(path, "wb") as handle:
            handle.write(content)
        return path

    def test_recomputes_hash_and_size_from_disk(self):
        payload = b"notarized-and-stapled-bytes"
        self._write("app.dmg", payload)
        # Manifest carries the PRE-notarization hash/size, as it would after stapling.
        manifest = parse_manifest(_manifest_for("app.dmg", "STALEHASH", 999, block=42))
        refreshed = refresh_entries(manifest, self.dist)
        entry = refreshed["files"][0]
        expected = base64.b64encode(hashlib.sha512(payload).digest()).decode()
        self.assertEqual(entry["sha512"], expected)
        self.assertEqual(entry["size"], len(payload))
        # Top-level mirrors the primary entry, so both places agree.
        self.assertEqual(refreshed["sha512"], expected)
        self.assertEqual(refreshed["path"], "app.dmg")

    def test_drops_blockmapsize_when_bytes_changed(self):
        self._write("app.dmg", b"changed")
        manifest = parse_manifest(_manifest_for("app.dmg", "STALEHASH", 999, block=42))
        entry = refresh_entries(manifest, self.dist)["files"][0]
        self.assertNotIn("blockMapSize", entry)

    def test_keeps_blockmapsize_when_bytes_unchanged(self):
        payload = b"untouched"
        self._write("app.AppImage", payload)
        manifest = parse_manifest(_manifest_for("app.AppImage", sha512_base64(os.path.join(self.dist, "app.AppImage")), len(payload), block=42))
        entry = refresh_entries(manifest, self.dist)["files"][0]
        self.assertEqual(entry["blockMapSize"], 42)

    def test_missing_staged_file_is_fatal(self):
        manifest = parse_manifest(_manifest_for("absent.dmg", "AAAA", 1))
        with self.assertRaises(SystemExit):
            refresh_entries(manifest, self.dist)


class StageTests(unittest.TestCase):
    def setUp(self):
        self.root = tempfile.mkdtemp()
        self.addCleanup(shutil.rmtree, self.root)
        self.build = os.path.join(self.root, "build")
        self.dist = os.path.join(self.root, "dist")

    def _format_dir(self, fmt, artifact, payload, manifest_name, block=None):
        directory = os.path.join(self.build, "compose", "binaries", "main", fmt)
        os.makedirs(directory, exist_ok=True)
        with open(os.path.join(directory, artifact), "wb") as handle:
            handle.write(payload)
        sha = sha512_base64(os.path.join(directory, artifact))
        with open(os.path.join(directory, manifest_name), "w", encoding="utf-8") as handle:
            handle.write(_manifest_for(artifact, sha, len(payload), block=block))
        return directory

    def test_macos_two_manifests_become_one(self):
        # The exact layout Nucleus produces on macOS: Dmg and Zip are separate
        # electron-builder invocations, each writing its own latest-mac.yml.
        self._format_dir("dmg", "app-26.30.1.dmg", b"dmg-bytes", "latest-mac.yml")
        self._format_dir("zip", "app-26.30.1-mac.zip", b"zip-bytes", "latest-mac.yml")

        written = stage(self.build, self.dist)
        self.assertEqual(written, ["latest-mac.yml"])

        with open(os.path.join(self.dist, "latest-mac.yml"), encoding="utf-8") as handle:
            merged = parse_manifest(handle.read())
        urls = [entry["url"] for entry in merged["files"]]
        self.assertEqual(urls, ["app-26.30.1-mac.zip", "app-26.30.1.dmg"])
        # Both installers are staged for upload, not just the surviving manifest's one.
        self.assertTrue(os.path.isfile(os.path.join(self.dist, "app-26.30.1.dmg")))
        self.assertTrue(os.path.isfile(os.path.join(self.dist, "app-26.30.1-mac.zip")))
        # And the recorded hashes describe the staged bytes.
        for entry in merged["files"]:
            self.assertEqual(entry["sha512"], sha512_base64(os.path.join(self.dist, entry["url"])))

    def test_different_os_manifests_stay_separate(self):
        self._format_dir("AppImage", "app.AppImage", b"linux", "latest-linux.yml")
        self._format_dir("msi", "app.msi", b"windows", "latest.yml")
        written = stage(self.build, self.dist)
        self.assertEqual(written, ["latest-linux.yml", "latest.yml"])

    def test_blockmap_sidecar_is_staged(self):
        directory = self._format_dir("AppImage", "app.AppImage", b"linux", "latest-linux.yml", block=7)
        with open(os.path.join(directory, "app.AppImage.blockmap"), "wb") as handle:
            handle.write(b"blockmap")
        stage(self.build, self.dist)
        self.assertTrue(os.path.isfile(os.path.join(self.dist, "app.AppImage.blockmap")))

    def test_no_manifest_is_fatal(self):
        os.makedirs(self.build, exist_ok=True)
        with self.assertRaises(SystemExit):
            stage(self.build, self.dist)

    def test_manifest_referencing_missing_artifact_is_fatal(self):
        directory = os.path.join(self.build, "compose", "binaries", "main", "dmg")
        os.makedirs(directory)
        with open(os.path.join(directory, "latest-mac.yml"), "w", encoding="utf-8") as handle:
            handle.write(_manifest_for("vanished.dmg", "AAAA", 5))
        with self.assertRaises(SystemExit):
            stage(self.build, self.dist)


if __name__ == "__main__":
    unittest.main()
