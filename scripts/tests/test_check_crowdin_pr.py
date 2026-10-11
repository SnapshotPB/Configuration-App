"""Unit tests for ``check_crowdin_pr.py``.

The first groups test the pure functions. The last group builds a small git repository in a
temporary directory and runs the command, so that the git calls and the exit codes are
tested too.
"""

from __future__ import annotations

import contextlib
import io
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from check_crowdin_pr import (  # noqa: E402
    SOURCE_FILE,
    CheckError,
    check_changes,
    compare_strings,
    main,
    parse_strings,
)

ES = "core/resources/src/commonMain/composeResources/values-es/strings.xml"
DE = "core/resources/src/commonMain/composeResources/values-de/strings.xml"

LONG = "Visualiza un ciclo de disparo desde el flanco del gatillo."
SHORT = "Visualiza el ciclo de disparo."


def strings_xml(*entries: str) -> str:
    body = "\n".join(f"    {entry}" for entry in entries)
    return f'<?xml version="1.0" encoding="utf-8"?>\n<resources>\n{body}\n</resources>\n'


def string(name: str, value: str) -> str:
    return f'<string name="{name}">{value}</string>'


class ParseStringsTest(unittest.TestCase):
    def test_reads_every_string(self) -> None:
        values, repeated = parse_strings(strings_xml(string("a", "x"), string("b", "y")))
        self.assertEqual(values, {"a": "x", "b": "y"})
        self.assertEqual(repeated, [])

    def test_resolves_entities(self) -> None:
        values, _ = parse_strings(strings_xml(string("a", "it&apos;s"), string("b", "it's")))
        self.assertEqual(values["a"], values["b"])

    def test_skips_comments(self) -> None:
        values, _ = parse_strings(strings_xml("<!-- note -->", string("a", "x")))
        self.assertEqual(values, {"a": "x"})

    def test_reports_a_repeated_name(self) -> None:
        _, repeated = parse_strings(strings_xml(string("a", "x"), string("a", "y")))
        self.assertEqual(repeated, ["a"])

    def test_keeps_plurals_apart_from_strings(self) -> None:
        text = strings_xml(
            string("n", "x"),
            '<plurals name="n"><item quantity="one">1</item></plurals>',
        )
        values, repeated = parse_strings(text)
        self.assertEqual(set(values), {"n", "plurals:n"})
        self.assertEqual(repeated, [])

    def test_sees_a_change_inside_plurals(self) -> None:
        one, _ = parse_strings(strings_xml('<plurals name="n"><item quantity="one">1</item></plurals>'))
        two, _ = parse_strings(strings_xml('<plurals name="n"><item quantity="one">2</item></plurals>'))
        self.assertNotEqual(one, two)

    def test_rejects_broken_xml(self) -> None:
        with self.assertRaises(CheckError):
            parse_strings("<resources><string name='a'>x</resources>")


class CompareStringsTest(unittest.TestCase):
    def test_permits_an_unchanged_file(self) -> None:
        text = strings_xml(string("a", LONG))
        self.assertEqual(compare_strings(ES, text, text), [])

    def test_permits_a_change_that_only_moves_a_line(self) -> None:
        base = strings_xml(string("a", "x"), string("b", "y"))
        head = strings_xml(string("b", "y"), "<!-- moved -->", string("a", "x"))
        self.assertEqual(compare_strings(ES, base, head), [])

    def test_fails_a_replaced_value(self) -> None:
        """This is the change that Crowdin made in #78 to #80."""
        problems = compare_strings(
            ES, strings_xml(string("a", LONG)), strings_xml(string("a", SHORT))
        )
        self.assertEqual([p.key for p in problems], ["a"])
        self.assertIn("replaces", problems[0].message)

    def test_fails_a_quote_wrapper(self) -> None:
        problems = compare_strings(
            ES, strings_xml(string("a", "No se pudo")), strings_xml(string("a", '"No se pudo"'))
        )
        self.assertEqual([p.key for p in problems], ["a"])

    def test_fails_a_removed_value(self) -> None:
        problems = compare_strings(
            ES, strings_xml(string("a", LONG), string("b", "y")), strings_xml(string("b", "y"))
        )
        self.assertEqual([p.key for p in problems], ["a"])
        self.assertIn("removes", problems[0].message)

    def test_permits_a_new_string(self) -> None:
        base = strings_xml(string("a", LONG))
        head = strings_xml(string("a", LONG), string("b", "nuevo"))
        self.assertEqual(compare_strings(ES, base, head), [])

    def test_permits_a_value_for_an_empty_string(self) -> None:
        base = strings_xml(string("a", ""))
        head = strings_xml(string("a", "nuevo"))
        self.assertEqual(compare_strings(ES, base, head), [])

    def test_permits_a_new_file(self) -> None:
        self.assertEqual(compare_strings(ES, None, strings_xml(string("a", "x"))), [])

    def test_fails_a_deleted_file(self) -> None:
        problems = compare_strings(ES, strings_xml(string("a", "x")), None)
        self.assertEqual(len(problems), 1)
        self.assertIsNone(problems[0].key)
        self.assertIn("deletes", problems[0].message)

    def test_fails_broken_xml_in_the_head(self) -> None:
        problems = compare_strings(ES, strings_xml(string("a", "x")), "<resources>")
        self.assertEqual(len(problems), 1)
        self.assertIn("not valid XML", problems[0].message)

    def test_fails_a_repeated_name_in_the_head(self) -> None:
        base = strings_xml(string("a", "x"))
        head = strings_xml(string("a", "x"), string("a", "x"))
        problems = compare_strings(ES, base, head)
        self.assertEqual([p.key for p in problems], ["a"])

    def test_strict_mode_fails_a_new_string(self) -> None:
        base = strings_xml(string("a", "x"))
        head = strings_xml(string("a", "x"), string("b", "y"))
        problems = compare_strings(SOURCE_FILE, base, head, strict=True)
        self.assertEqual([p.key for p in problems], ["b"])

    def test_strict_mode_fails_a_value_for_an_empty_string(self) -> None:
        problems = compare_strings(
            SOURCE_FILE, strings_xml(string("a", "")), strings_xml(string("a", "x")), strict=True
        )
        self.assertEqual([p.key for p in problems], ["a"])


def reader(files: dict[str, str]):
    return lambda path: files.get(path)


class CheckChangesTest(unittest.TestCase):
    def test_permits_new_strings_in_every_locale(self) -> None:
        base = {ES: strings_xml(string("a", "x")), DE: strings_xml(string("a", "x"))}
        head = {
            ES: strings_xml(string("a", "x"), string("b", "y")),
            DE: strings_xml(string("a", "x"), string("b", "z")),
        }
        self.assertEqual(check_changes([ES, DE], reader(base), reader(head)), [])

    def test_fails_any_change_to_the_source_file(self) -> None:
        base = {SOURCE_FILE: strings_xml(string("a", "Couldn’t read"))}
        head = {SOURCE_FILE: strings_xml(string("a", '"Couldn\'t read"'))}
        problems = check_changes([SOURCE_FILE], reader(base), reader(head))
        self.assertEqual([p.key for p in problems], [None, "a"])
        self.assertIn("English source file", problems[0].message)

    def test_fails_a_file_outside_the_translations(self) -> None:
        problems = check_changes(["crowdin.yml"], reader({}), reader({}))
        self.assertEqual(len(problems), 1)
        self.assertEqual(problems[0].path, "crowdin.yml")

    def test_fails_a_file_beside_a_translation(self) -> None:
        other = "core/resources/src/commonMain/composeResources/values-es/other.xml"
        self.assertEqual(len(check_changes([other], reader({}), reader({}))), 1)

    def test_reports_every_locale(self) -> None:
        base = {ES: strings_xml(string("a", LONG)), DE: strings_xml(string("a", "x"))}
        head = {ES: strings_xml(string("a", SHORT)), DE: strings_xml(string("a", "y"))}
        problems = check_changes([ES, DE], reader(base), reader(head))
        self.assertEqual(sorted(p.path for p in problems), sorted([DE, ES]))


# The runner has no git identity, and a developer can sign every commit by default.
GIT_IDENTITY = (
    "-c", "user.name=Test",
    "-c", "user.email=test@example.com",
    "-c", "commit.gpgsign=false",
)


class CommandTest(unittest.TestCase):
    """Run the command against a real git repository."""

    def setUp(self) -> None:
        self._directory = tempfile.TemporaryDirectory()
        self.repo = Path(self._directory.name)
        self.git("init", "--quiet")
        self.write(SOURCE_FILE, strings_xml(string("a", "English")))
        self.write(ES, strings_xml(string("a", LONG)))
        self.base = self.commit("base")

    def tearDown(self) -> None:
        self._directory.cleanup()

    def git(self, *args: str) -> str:
        result = subprocess.run(
            ["git", *GIT_IDENTITY, *args],
            cwd=self.repo,
            capture_output=True,
            text=True,
            check=True,
        )
        return result.stdout.strip()

    def write(self, path: str, text: str) -> None:
        target = self.repo / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(text, encoding="utf-8")

    def commit(self, message: str) -> str:
        self.git("add", "--all")
        self.git("commit", "--quiet", "--allow-empty", "-m", message)
        return self.git("rev-parse", "HEAD")

    def run_main(self, *args: str) -> tuple[int, str]:
        output = io.StringIO()
        with contextlib.redirect_stdout(output), contextlib.redirect_stderr(output):
            code = main(["--repo", str(self.repo), *args])
        return code, output.getvalue()

    def test_passes_a_new_string(self) -> None:
        self.write(ES, strings_xml(string("a", LONG), string("b", "nuevo")))
        head = self.commit("add")
        code, _ = self.run_main("--base", self.base, "--head", head)
        self.assertEqual(code, 0)

    def test_fails_a_replaced_string(self) -> None:
        self.write(ES, strings_xml(string("a", SHORT)))
        head = self.commit("replace")
        code, output = self.run_main("--base", self.base, "--head", head)
        self.assertEqual(code, 1)
        self.assertIn("values-es/strings.xml: a:", output)

    def test_fails_a_deleted_locale(self) -> None:
        (self.repo / ES).unlink()
        head = self.commit("delete")
        code, output = self.run_main("--base", self.base, "--head", head)
        self.assertEqual(code, 1)
        self.assertIn("deletes the file", output)

    def test_defaults_to_the_first_parent_of_a_merge(self) -> None:
        """A PR job checks out a merge commit whose first parent is the base branch."""
        main_branch = self.git("rev-parse", "--abbrev-ref", "HEAD")
        self.git("checkout", "--quiet", "-b", "l10n_master")
        self.write(ES, strings_xml(string("a", SHORT)))
        self.commit("crowdin")
        self.git("checkout", "--quiet", main_branch)
        self.write(SOURCE_FILE, strings_xml(string("a", "English"), string("z", "Other")))
        self.commit("master moves on")
        self.git("merge", "--quiet", "--no-ff", "-m", "merge", "l10n_master")
        code, output = self.run_main()
        self.assertEqual(code, 1)
        self.assertIn("values-es/strings.xml: a:", output)
        self.assertNotIn("English source file", output)

    def test_reports_an_unknown_commit(self) -> None:
        code, output = self.run_main("--base", "0" * 40, "--head", self.base)
        self.assertEqual(code, 2)
        self.assertIn("is not a commit", output)


if __name__ == "__main__":
    unittest.main()
