#!/usr/bin/env python3
"""Unit tests for scripts/select_xcode.py.

Run with: python3 -m unittest discover -s scripts/tests
"""

import contextlib
import io
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from select_xcode import (  # noqa: E402
    main,
    parse_version,
    select_xcode,
    xcode_version,
)


class ParseVersionTests(unittest.TestCase):
    def test_valid(self):
        self.assertEqual(parse_version("27.1"), (27, 1, 0))
        self.assertEqual(parse_version("27"), (27, 0, 0))
        self.assertEqual(parse_version("26.4.1"), (26, 4, 1))

    def test_strips_whitespace(self):
        self.assertEqual(parse_version("  27.1 "), (27, 1, 0))

    def test_invalid(self):
        for bad in ("", "27.x", "27.1.2.3", "v27", "27."):
            with self.subTest(value=bad):
                with self.assertRaises(ValueError):
                    parse_version(bad)


class XcodeVersionTests(unittest.TestCase):
    def test_valid(self):
        self.assertEqual(xcode_version("Xcode_27.1.app"), (27, 1, 0))
        self.assertEqual(xcode_version("Xcode_27.app"), (27, 0, 0))
        self.assertEqual(xcode_version("Xcode_26.4.1.app"), (26, 4, 1))
        self.assertEqual(xcode_version("Xcode_27.1.0.app"), (27, 1, 0))

    def test_invalid(self):
        invalid_names = (
            "Xcode.app",
            "Xcode_27_beta_3.app",
            "Xcode_27.3_beta.app",
            "Xcode_27.1.app.bak",
            "Xcode-27.1.app",
            "Xcode_27.1",
        )
        for name in invalid_names:
            with self.subTest(name=name):
                self.assertIsNone(xcode_version(name))


class SelectXcodeTests(unittest.TestCase):
    def test_xcode_27_runner_directories(self):
        names = ["Xcode_27.app", "Xcode_27.1.app", "Xcode_27.2.app"]
        self.assertEqual(select_xcode(names, (27, 1, 0)), "Xcode_27.2.app")

    def test_minimum_boundary(self):
        names = ["Xcode_27.app", "Xcode_27.1.app", "Xcode_27.2.app"]
        self.assertEqual(select_xcode(names, (27, 2, 0)), "Xcode_27.2.app")
        self.assertIsNone(select_xcode(names, (27, 3, 0)))

    def test_only_27_zero_below_minimum(self):
        self.assertIsNone(select_xcode(["Xcode_27.app"], (27, 1, 0)))

    def test_macos_26_names_give_none(self):
        names = ["Xcode_26.6.app", "Xcode_26.5.app", "Xcode_26.4.1.app"]
        self.assertIsNone(select_xcode(names, (27, 1, 0)))

    def test_ignores_newer_major_version(self):
        names = ["Xcode_28.0.app", "Xcode_27.1.app"]
        self.assertEqual(select_xcode(names, (27, 1, 0)), "Xcode_27.1.app")

    def test_selects_higher_patch_version(self):
        names = ["Xcode_27.1.app", "Xcode_27.1.1.app"]
        self.assertEqual(select_xcode(names, (27, 1, 0)), "Xcode_27.1.1.app")

    def test_same_version_prefers_shorter_name(self):
        names = ["Xcode_27.1.0.app", "Xcode_27.1.app"]
        self.assertEqual(select_xcode(names, (27, 1, 0)), "Xcode_27.1.app")

    def test_same_version_and_length_prefers_sort_order(self):
        names = ["Xcode_27.1.00.app", "Xcode_27.01.0.app"]
        self.assertEqual(select_xcode(names, (27, 1, 0)), "Xcode_27.01.0.app")

    def test_ignores_beta_names(self):
        names = ["Xcode_27.1.app", "Xcode_27.3_beta.app"]
        self.assertEqual(select_xcode(names, (27, 1, 0)), "Xcode_27.1.app")

    def test_empty_list_gives_none(self):
        self.assertIsNone(select_xcode([], (27, 1, 0)))


class MainTests(unittest.TestCase):
    def test_skips_symlinks_and_prints_full_path(self):
        with tempfile.TemporaryDirectory() as folder:
            for name in ("Xcode_27.app", "Xcode_27.1.app", "Xcode_27.2.app"):
                os.mkdir(os.path.join(folder, name))
            os.symlink(
                os.path.join(folder, "Xcode_27.1.app"),
                os.path.join(folder, "Xcode_27.9.app"),
            )

            stdout = io.StringIO()
            stderr = io.StringIO()
            with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                code = main(["--minimum", "27.1", "--applications", folder])

            self.assertEqual(code, 0)
            self.assertEqual(stdout.getvalue(), os.path.join(folder, "Xcode_27.2.app") + "\n")
            self.assertEqual(stderr.getvalue(), "")

    def test_only_xcode_26_returns_one_with_diagnostics(self):
        with tempfile.TemporaryDirectory() as folder:
            os.mkdir(os.path.join(folder, "Xcode_26.6.app"))

            stdout = io.StringIO()
            stderr = io.StringIO()
            with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                code = main(["--minimum", "27.1", "--applications", folder])

            self.assertEqual(code, 1)
            self.assertEqual(stdout.getvalue(), "")
            self.assertIn("27.1", stderr.getvalue())
            self.assertIn("Xcode_26.6.app", stderr.getvalue())

    def test_empty_folder_reports_none_found(self):
        with tempfile.TemporaryDirectory() as folder:
            stdout = io.StringIO()
            stderr = io.StringIO()
            with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                code = main(["--minimum", "27.1", "--applications", folder])

            self.assertEqual(code, 1)
            self.assertEqual(stdout.getvalue(), "")
            self.assertIn("Found: none", stderr.getvalue())

    def test_absent_folder_returns_one(self):
        with tempfile.TemporaryDirectory() as folder:
            absent = os.path.join(folder, "no-such-folder")

            stdout = io.StringIO()
            stderr = io.StringIO()
            with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
                code = main(["--minimum", "27.1", "--applications", absent])

            self.assertEqual(code, 1)
            self.assertEqual(stdout.getvalue(), "")
            self.assertNotEqual(stderr.getvalue().strip(), "")

    def test_bad_minimum_exits_with_code_two(self):
        stderr = io.StringIO()
        with contextlib.redirect_stderr(stderr):
            with self.assertRaises(SystemExit) as context:
                main(["--minimum", "bad"])
        self.assertEqual(context.exception.code, 2)


if __name__ == "__main__":
    unittest.main(verbosity=2)
