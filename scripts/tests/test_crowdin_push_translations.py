"""Unit tests for ``crowdin_push_translations.py``.

These tests send no request to Crowdin. They pin the functions that decide what the script
sends: the locale map, the values it reads from the repository, the keys and the token it
selects, the rule that says which translation Crowdin exports, and the comparison that
turns those into a list of changes. A fake transport tests the page loop of the client.
"""

from __future__ import annotations

import argparse
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from crowdin_push_translations import (  # noqa: E402
    CROWDIN_CONFIG,
    PAGE_SIZE,
    SOURCE_STRINGS,
    CrowdinClient,
    CrowdinError,
    choose_project,
    decide,
    effective_translation,
    index_strings,
    load_token,
    parse_language_map,
    plan_changes,
    read_strings,
    select_keys,
    shorten,
)


def translation(identifier: int, text: str) -> dict:
    """Build the part of a Crowdin translation record these tests read."""
    return {"id": identifier, "text": text}


LONG = "Visualiza un ciclo de disparo desde el flanco del gatillo."
SHORT = "Visualiza el ciclo de disparo."


class EffectiveTranslationTest(unittest.TestCase):
    """Pin the rule that produced the loop: with no approval, the newest value wins."""

    def test_reports_none_when_there_is_no_translation(self) -> None:
        self.assertIsNone(effective_translation([], set()))

    def test_takes_the_newest_when_nothing_is_approved(self) -> None:
        pool = [translation(1687, LONG), translation(1693, SHORT)]
        self.assertEqual(effective_translation(pool, set())["text"], SHORT)

    def test_takes_the_approved_one_over_a_newer_one(self) -> None:
        pool = [translation(1687, LONG), translation(1693, SHORT)]
        self.assertEqual(effective_translation(pool, {1687})["text"], LONG)

    def test_takes_the_newest_approval_when_several_are_approved(self) -> None:
        pool = [translation(1, "a"), translation(2, "b")]
        self.assertEqual(effective_translation(pool, {1, 2})["text"], "b")


class DecideTest(unittest.TestCase):
    def test_approves_a_correct_value_that_lost_on_age(self) -> None:
        pool = [translation(1687, LONG), translation(1693, SHORT)]
        self.assertEqual(decide(LONG, pool, set()), ("approve", 1687, SHORT))

    def test_adds_when_no_translation_matches(self) -> None:
        pool = [translation(1693, SHORT)]
        self.assertEqual(decide(LONG, pool, set()), ("add", None, SHORT))

    def test_adds_when_there_is_no_translation_at_all(self) -> None:
        self.assertEqual(decide(LONG, [], set()), ("add", None, None))

    def test_does_nothing_when_the_correct_value_is_approved(self) -> None:
        pool = [translation(1687, LONG), translation(1693, SHORT)]
        self.assertEqual(decide(LONG, pool, {1687}), ("none", 1687, LONG))


CONFIG = """\
base_path: .
preserve_hierarchy: true
files:
  - source: /core/resources/src/commonMain/composeResources/values/strings.xml
    translation: /core/resources/src/commonMain/composeResources/values-%android_code%/%original_file_name%
    type: android
    # A comment between the marker and the block must not stop the reader.
    languages_mapping:
      android_code:
        de-DE: de
        es-ES: es
        es-MX: es-rMX
        es-US: es-rUS
        fr-FR: fr
        fr-CA: fr-rCA
"""


class ParseLanguageMapTest(unittest.TestCase):
    def test_reads_every_pair(self) -> None:
        self.assertEqual(
            parse_language_map(CONFIG),
            {
                "de-DE": "de",
                "es-ES": "es",
                "es-MX": "es-rMX",
                "es-US": "es-rUS",
                "fr-FR": "fr",
                "fr-CA": "fr-rCA",
            },
        )

    def test_stops_at_the_end_of_the_block(self) -> None:
        extended = CONFIG + "\nsomething_else: value\n"
        self.assertNotIn("something_else", parse_language_map(extended))

    def test_rejects_a_file_with_no_block(self) -> None:
        with self.assertRaises(CrowdinError):
            parse_language_map("base_path: .\n")

    def test_rejects_an_empty_block(self) -> None:
        with self.assertRaises(CrowdinError):
            parse_language_map("      android_code:\n\nfiles:\n")


    def test_reads_the_repository_config(self) -> None:
        """The real crowdin.yml must map every language directory in the repository."""
        mapping = parse_language_map(CROWDIN_CONFIG.read_text(encoding="utf-8"))
        directories = {
            path.parent.name.removeprefix("values-")
            for path in SOURCE_STRINGS.parent.parent.glob("values-*/strings.xml")
        }
        self.assertEqual(set(mapping.values()), directories)


class ReadStringsTest(unittest.TestCase):
    def setUp(self) -> None:
        self._directory = tempfile.TemporaryDirectory()
        self.path = Path(self._directory.name) / "strings.xml"

    def tearDown(self) -> None:
        self._directory.cleanup()

    def write(self, body: str) -> None:
        self.path.write_text(
            f'<?xml version="1.0" encoding="utf-8"?>\n<resources>\n{body}\n</resources>\n',
            encoding="utf-8",
        )

    def test_reads_an_accented_value(self) -> None:
        self.write('    <string name="a">Visualiza la pulsación</string>')
        self.assertEqual(read_strings(self.path), {"a": "Visualiza la pulsación"})

    def test_resolves_an_entity(self) -> None:
        self.write('    <string name="a">Chrome &amp; Edge</string>')
        self.assertEqual(read_strings(self.path)["a"], "Chrome & Edge")

    def test_keeps_a_format_specifier(self) -> None:
        self.write('    <string name="a">No se recibió nada de %1$s.</string>')
        self.assertEqual(read_strings(self.path)["a"], "No se recibió nada de %1$s.")

    def test_keeps_the_order_of_the_file(self) -> None:
        self.write('    <string name="b">x</string>\n    <string name="a">y</string>')
        self.assertEqual(list(read_strings(self.path)), ["b", "a"])

    def test_rejects_a_missing_file(self) -> None:
        with self.assertRaises(CrowdinError):
            read_strings(self.path)

    def test_reads_the_repository_source_file(self) -> None:
        self.assertIn("app_name", read_strings(SOURCE_STRINGS))


def source(identifier: str, string_id: int, file_id: int = 1) -> dict:
    return {"id": string_id, "identifier": identifier, "fileId": file_id, "text": identifier}


class IndexStringsTest(unittest.TestCase):
    def test_finds_each_key(self) -> None:
        found, missing = index_strings([source("a", 1), source("b", 2)], ["a", "b"])
        self.assertEqual({key: record["id"] for key, record in found.items()}, {"a": 1, "b": 2})
        self.assertEqual(missing, [])

    def test_reports_a_key_that_crowdin_does_not_hold(self) -> None:
        found, missing = index_strings([source("a", 1)], ["a", "new"])
        self.assertEqual(list(found), ["a"])
        self.assertEqual(missing, ["new"])

    def test_rejects_a_key_in_two_files(self) -> None:
        with self.assertRaises(CrowdinError):
            index_strings([source("a", 1, 1), source("a", 2, 2)], ["a"])

    def test_ignores_a_duplicate_that_was_not_asked_for(self) -> None:
        found, _ = index_strings([source("a", 1), source("z", 2, 1), source("z", 3, 2)], ["a"])
        self.assertEqual(list(found), ["a"])


def arguments(keys: list[str], all_keys: bool = False) -> argparse.Namespace:
    return argparse.Namespace(keys=keys, all=all_keys)


class SelectKeysTest(unittest.TestCase):
    SOURCE = ["a", "b", "c"]

    def test_all_takes_every_source_key(self) -> None:
        self.assertEqual(select_keys(arguments([], True), self.SOURCE), self.SOURCE)

    def test_takes_the_named_keys(self) -> None:
        self.assertEqual(select_keys(arguments(["c", "a"]), self.SOURCE), ["c", "a"])

    def test_requires_a_choice(self) -> None:
        with self.assertRaises(CrowdinError):
            select_keys(arguments([]), self.SOURCE)

    def test_rejects_both(self) -> None:
        with self.assertRaises(CrowdinError):
            select_keys(arguments(["a"], True), self.SOURCE)

    def test_rejects_an_unknown_key(self) -> None:
        with self.assertRaises(CrowdinError):
            select_keys(arguments(["absent"]), self.SOURCE)


class LoadTokenTest(unittest.TestCase):
    def setUp(self) -> None:
        self._directory = tempfile.TemporaryDirectory()
        self.path = Path(self._directory.name) / "token"

    def tearDown(self) -> None:
        self._directory.cleanup()

    def test_reads_the_environment(self) -> None:
        self.assertEqual(load_token(None, {"CROWDIN_TOKEN": " secret\n"}), "secret")

    def test_prefers_an_explicit_file(self) -> None:
        self.path.write_text("from-file\n", encoding="utf-8")
        self.assertEqual(load_token(self.path, {"CROWDIN_TOKEN": "from-env"}), "from-file")

    def test_rejects_no_token(self) -> None:
        with self.assertRaises(CrowdinError):
            load_token(None, {})

    def test_rejects_an_empty_file(self) -> None:
        self.path.write_text("\n", encoding="utf-8")
        with self.assertRaises(CrowdinError):
            load_token(self.path, {"CROWDIN_TOKEN": "from-env"})

    def test_rejects_a_missing_file(self) -> None:
        with self.assertRaises(CrowdinError):
            load_token(self.path, {})


class FakeClient(CrowdinClient):
    """Answer list requests from memory and record each path."""

    def __init__(self, total: int) -> None:
        super().__init__("token")
        self.total = total
        self.paths: list[str] = []

    def _request(self, method: str, path: str, body: dict | None = None) -> dict:
        self.paths.append(path)
        offset = int(path.split("offset=")[1])
        count = max(0, min(PAGE_SIZE, self.total - offset))
        return {"data": [{"data": {"id": offset + i}} for i in range(count)]}


class ListPagesTest(unittest.TestCase):
    def test_reads_one_short_page(self) -> None:
        client = FakeClient(3)
        self.assertEqual(len(client.list_strings(7)), 3)
        self.assertEqual(len(client.paths), 1)

    def test_reads_every_page(self) -> None:
        client = FakeClient(PAGE_SIZE * 2 + 1)
        records = client.list_strings(7)
        self.assertEqual([record["id"] for record in records], list(range(PAGE_SIZE * 2 + 1)))
        self.assertEqual(len(client.paths), 3)

    def test_stops_after_an_empty_page(self) -> None:
        client = FakeClient(PAGE_SIZE)
        self.assertEqual(len(client.list_strings(7)), PAGE_SIZE)
        self.assertEqual(len(client.paths), 2)

    def test_keeps_the_filters(self) -> None:
        client = FakeClient(0)
        client.list_translations(7, 42, "es-MX")
        self.assertIn("stringId=42", client.paths[0])
        self.assertIn("languageId=es-MX", client.paths[0])


class PlanChangesTest(unittest.TestCase):
    DIRECTORIES = {"es-ES": "es", "es-MX": "es-rMX", "es-US": "es-rUS"}

    def test_reports_nothing_when_the_repo_text_is_approved(self) -> None:
        state = {"es-ES": ([translation(1, LONG)], {1})}
        self.assertEqual(plan_changes("k", 7, {"es-ES": LONG}, state, self.DIRECTORIES), [])

    def test_reports_a_value_that_lost_on_age(self) -> None:
        state = {"es-ES": ([translation(1687, LONG), translation(1693, SHORT)], set())}
        changes = plan_changes("k", 7, {"es-ES": LONG}, state, self.DIRECTORIES)
        self.assertEqual(len(changes), 1)
        self.assertEqual(changes[0].language_id, "es-ES")
        self.assertEqual(changes[0].action, "approve")
        self.assertEqual(changes[0].translation_id, 1687)
        self.assertEqual(changes[0].repo_text, LONG)
        self.assertEqual(changes[0].crowdin_text, SHORT)
        self.assertEqual(changes[0].string_id, 7)
        self.assertEqual(changes[0].directory, "es")
        self.assertFalse(changes[0].is_absent)

    def test_reports_an_absent_translation(self) -> None:
        changes = plan_changes("k", 7, {"es-MX": LONG}, {"es-MX": ([], set())}, self.DIRECTORIES)
        self.assertEqual(len(changes), 1)
        self.assertEqual(changes[0].action, "add")
        self.assertIsNone(changes[0].translation_id)
        self.assertTrue(changes[0].is_absent)

    def test_covers_every_language_that_needs_a_change(self) -> None:
        state = {
            "es-ES": ([translation(1, LONG)], {1}),
            "es-MX": ([translation(2, LONG), translation(3, SHORT)], set()),
            "es-US": ([], set()),
        }
        changes = plan_changes(
            "k",
            7,
            {"es-ES": LONG, "es-MX": LONG, "es-US": LONG},
            state,
            self.DIRECTORIES,
        )
        self.assertEqual([c.language_id for c in changes], ["es-MX", "es-US"])
        self.assertEqual([c.action for c in changes], ["approve", "add"])

    def test_is_safe_to_repeat(self) -> None:
        """A second run after an approval must plan nothing."""
        state = {"es-ES": ([translation(1687, LONG), translation(1693, SHORT)], {1687})}
        self.assertEqual(plan_changes("k", 7, {"es-ES": LONG}, state, self.DIRECTORIES), [])


class ChooseProjectTest(unittest.TestCase):
    PROJECTS = [
        {"id": 1, "identifier": "snapshot-pb", "name": "Snapshot PB"},
        {"id": 2, "identifier": "other", "name": "Other"},
    ]

    def test_takes_the_only_project(self) -> None:
        self.assertEqual(choose_project(self.PROJECTS[:1], None)["id"], 1)

    def test_matches_an_identifier(self) -> None:
        self.assertEqual(choose_project(self.PROJECTS, "snapshot-pb")["id"], 1)

    def test_matches_a_numeric_id(self) -> None:
        self.assertEqual(choose_project(self.PROJECTS, "2")["id"], 2)

    def test_refuses_to_guess(self) -> None:
        with self.assertRaises(CrowdinError):
            choose_project(self.PROJECTS, None)

    def test_rejects_an_unknown_selector(self) -> None:
        with self.assertRaises(CrowdinError):
            choose_project(self.PROJECTS, "absent")


class ShortenTest(unittest.TestCase):
    def test_marks_an_absent_value(self) -> None:
        self.assertEqual(shorten(None), "(no translation)")

    def test_keeps_a_short_value(self) -> None:
        self.assertEqual(shorten("corto"), "corto")

    def test_cuts_a_long_value(self) -> None:
        self.assertEqual(len(shorten("x" * 400, width=20)), 20)


if __name__ == "__main__":
    unittest.main()
