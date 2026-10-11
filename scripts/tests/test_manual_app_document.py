#!/usr/bin/env python3
"""Unit tests for scripts/manual_app_document.py.

Run with: python3 -m unittest discover -s scripts/tests

The tests of the pure functions use small sources in the test. The last test builds the
document from the real sources of the repository, with a fixed version, so a field of the
app that has no words fails here before it fails in the manual build.
"""

import json
import os
import sys
import tomllib
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from manual_app_document import (  # noqa: E402
    FIELDS_PATH,
    PROTO_DIR,
    STRINGS_PATH,
    WORDS_PATH,
    DocumentError,
    build_document,
    load_sources,
    locale_tag,
    localized,
    parse_fields,
    parse_proto_fields,
    parse_strings,
    version_from_describe,
)

REPO = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def en(text):
    return [{"lang": "en", "text": text}]


STRINGS_EN = """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="profile_screen_details_title">Profile</string>
    <string name="configure_device_screen_title">Configure Device</string>
    <string name="profile_section_basic_function">Basic Function</string>
    <string name="profile_section_eye">Eye</string>
    <string name="profile_label_fire_mode">Fire Mode</string>
    <string name="profile_label_fire_rate_cap">Fire Rate Cap</string>
    <string name="profile_label_eye_threshold">Eye Threshold</string>
    <string name="configure_device_screen_language_label">Board\\'s Language</string>
</resources>
"""

STRINGS_DE = """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="profile_screen_details_title">Profil</string>
    <string name="configure_device_screen_title">Gerät einrichten</string>
    <string name="profile_section_basic_function">Grundfunktion</string>
    <string name="profile_label_fire_mode">Feuermodus</string>
    <string name="profile_label_eye_threshold">Eye Threshold</string>
</resources>
"""

FIELDS = json.dumps(
    [
        {"key": "FIRE_RATE_CAP", "unit": "TENTHS_BPS", "min": 0, "max": 1000, "group": "BASIC", "affectsMaxBps": True},
        {"key": "EYE_THRESHOLD", "unit": "PERCENT", "min": 1, "max": 100, "group": "EYE", "affectsMaxBps": False},
    ]
)

PROTO = """
syntax = "proto3";
message Profile {
  string name = 1; // the name
  uint32 fire_rate_cap = 3;
  oneof board_config {
    AutocockerConfig autococker = 6;
  }
}
message AutocockerConfig {
  AutocockerFireMode fire_mode = 1;
  uint32 eye_threshold = 3;
}
message DeviceConfig {
  Language language = 4;
}
enum Language {
  LANGUAGE_UNSPECIFIED = 0;
  LANGUAGE_EN = 1;
}
"""

WORDS = """
[app]
name = "App"
protocol_version = "1.0.0"
[targets.android]
name = "Android"
[targets.wear]
name = "Wear OS"
description = { en = "The watch.", de = "Die Uhr." }
[paths]
profile = "{profile_screen_details_title} > {section} > {label}"
device = "{configure_device_screen_title} > {label}"
[paths.wear]
profile = "Tune > {label}"
device = "{label}"

[settings."autococker.fire_mode"]
section = "profile_section_basic_function"
description = "The mode."
[settings."profile.fire_rate_cap"]
description = "The cap."
targets = ["android"]
[settings."autococker.eye_threshold"]
description = { en = "The threshold.", de = "Die Schwelle." }
[settings."autococker.eye_threshold".overrides.wear]
editable = false
description = "On the watch."
[settings."device_config.language"]
label = "configure_device_screen_language_label"
description = "The language."

[topics."topic.lockout"]
title = "Lockout"
description = "The lockout."
editable = false
"""

EYE_ENTRY = '''[settings."autococker.eye_threshold"]
description = { en = "The threshold.", de = "Die Schwelle." }
[settings."autococker.eye_threshold".overrides.wear]
editable = false
description = "On the watch."
'''


def strings():
    return {"en": parse_strings(STRINGS_EN), "de": parse_strings(STRINGS_DE)}


def build(words=WORDS, fields=FIELDS, proto=PROTO, version="26.40.1", strs=None):
    return build_document(
        tomllib.loads(words), strs or strings(), parse_fields(fields), parse_proto_fields([proto]), version
    )


class SourceTests(unittest.TestCase):
    def test_strings_drop_the_escape_of_a_quote(self):
        s = parse_strings(STRINGS_EN)
        self.assertEqual(s["configure_device_screen_language_label"], "Board's Language")
        self.assertEqual(s["profile_label_fire_mode"], "Fire Mode")

    def test_a_resource_directory_gives_a_language_tag(self):
        self.assertEqual(locale_tag("values"), "en")
        self.assertEqual(locale_tag("values-de"), "de")
        self.assertEqual(locale_tag("values-es-rMX"), "es-MX")
        self.assertIsNone(locale_tag("drawable"))
        self.assertIsNone(locale_tag("values-night"))

    def test_fields_get_their_shared_key(self):
        f = parse_fields(FIELDS)
        self.assertEqual(f[0]["key"], "profile.fire_rate_cap")
        self.assertEqual(f[1], {"key": "autococker.eye_threshold", "field": "eye_threshold", "group": "EYE"})

    def test_proto_fields_by_message(self):
        p = parse_proto_fields([PROTO])
        self.assertEqual(p["Profile"], {"name", "fire_rate_cap", "autococker"})
        self.assertEqual(p["AutocockerConfig"], {"fire_mode", "eye_threshold"})
        self.assertNotIn("Language", p)

    def test_version_from_describe(self):
        self.assertEqual(version_from_describe("v26.40.1\n"), "26.40.1")
        self.assertIsNone(version_from_describe("v26.40.1-7-gabc1234"))
        self.assertIsNone(version_from_describe("26.40.1"))

    def test_a_text_of_the_words_file_is_localized(self):
        self.assertEqual(localized("A.", "x"), en("A."))
        self.assertEqual(
            localized({"de": "B.", "en": "A."}, "x"),
            [{"lang": "en", "text": "A."}, {"lang": "de", "text": "B."}],
        )
        with self.assertRaisesRegex(DocumentError, "no English"):
            localized({"de": "B."}, "x")


class DocumentTests(unittest.TestCase):
    def test_the_document_has_the_shape_of_the_schema(self):
        doc = build()
        self.assertEqual(doc["schema_version"], "2.0.0")
        self.assertEqual(doc["app"], {"name": en("App"), "version": "26.40.1", "protocol_version": "1.0.0"})
        self.assertEqual(list(doc["targets"]), ["android", "wear"])
        self.assertEqual(doc["targets"]["wear"]["description"], [{"lang": "en", "text": "The watch."}, {"lang": "de", "text": "Die Uhr."}])
        self.assertEqual(
            list(doc["settings"]),
            ["autococker.fire_mode", "profile.fire_rate_cap", "autococker.eye_threshold", "device_config.language"],
        )

    def test_a_title_and_a_path_come_in_each_language_of_the_app(self):
        s = build()["settings"]
        self.assertEqual(s["autococker.fire_mode"]["title"], [{"lang": "en", "text": "Fire Mode"}, {"lang": "de", "text": "Feuermodus"}])
        self.assertEqual(
            s["autococker.fire_mode"]["where"],
            [{"lang": "en", "text": "Profile > Basic Function > Fire Mode"}, {"lang": "de", "text": "Profil > Grundfunktion > Feuermodus"}],
        )
        # The German string of the threshold equals the English one, so the title has no German item.
        self.assertEqual(s["autococker.eye_threshold"]["title"], en("Eye Threshold"))
        # The section of the eye has no German string, so the path takes the English word for it.
        self.assertEqual(s["autococker.eye_threshold"]["where"][1], {"lang": "de", "text": "Profil > Eye > Eye Threshold"})
        self.assertEqual(s["device_config.language"]["where"][1], {"lang": "de", "text": "Gerät einrichten > Board's Language"})

    def test_a_target_with_another_path_gets_an_override(self):
        s = build()["settings"]
        self.assertEqual(
            s["autococker.fire_mode"]["targets"],
            {"android": {}, "wear": {"where": [{"lang": "en", "text": "Tune > Fire Mode"}, {"lang": "de", "text": "Tune > Feuermodus"}]}},
        )
        self.assertEqual(
            s["autococker.eye_threshold"]["targets"]["wear"],
            {"editable": False, "description": en("On the watch."), "where": en("Tune > Eye Threshold")},
        )
        self.assertEqual(s["device_config.language"]["targets"]["wear"], {"where": en("Board's Language")})

    def test_a_description_can_hold_each_language(self):
        s = build()["settings"]
        self.assertEqual(s["autococker.eye_threshold"]["description"], [{"lang": "en", "text": "The threshold."}, {"lang": "de", "text": "Die Schwelle."}])

    def test_a_targets_list_limits_the_entry(self):
        s = build()["settings"]
        self.assertEqual(s["profile.fire_rate_cap"]["targets"], {"android": {}})

    def test_a_topic_keeps_its_own_words(self):
        t = build()["topics"]["topic.lockout"]
        self.assertEqual(t, {"title": en("Lockout"), "description": en("The lockout."), "editable": False})

    def test_a_field_with_no_words_is_an_error(self):
        words = WORDS.replace(EYE_ENTRY, "")
        self.assertNotEqual(words, WORDS)
        with self.assertRaisesRegex(DocumentError, "eye_threshold has no words"):
            build(words=words)

    def test_a_key_that_the_protocol_lacks_is_an_error(self):
        words = WORDS + '\n[settings."autococker.eye_mode"]\nsection = "profile_section_eye"\ndescription = "x"\n'
        with self.assertRaisesRegex(DocumentError, "not a field of the protocol message AutocockerConfig"):
            build(words=words)

    def test_an_unknown_string_id_is_an_error(self):
        words = WORDS.replace("configure_device_screen_language_label", "no_such_string")
        with self.assertRaisesRegex(DocumentError, "no such string"):
            build(words=words)
        words = WORDS.replace("{configure_device_screen_title}", "{no_such_screen}")
        with self.assertRaisesRegex(DocumentError, "no such string"):
            build(words=words)

    def test_an_unknown_target_is_an_error(self):
        words = WORDS.replace('targets = ["android"]', 'targets = ["ios"]')
        with self.assertRaisesRegex(DocumentError, "no such target"):
            build(words=words)

    def test_an_override_outside_the_targets_list_is_an_error(self):
        words = WORDS.replace('targets = ["android"]', 'targets = ["android"]\n[settings."profile.fire_rate_cap".overrides.wear]\neditable = false')
        with self.assertRaisesRegex(DocumentError, "overrides a target"):
            build(words=words)

    def test_a_bad_version_is_an_error(self):
        with self.assertRaisesRegex(DocumentError, "not MAJOR.MINOR.PATCH"):
            build(version="v26.40.1")


class RepositoryTests(unittest.TestCase):
    """The real sources of the repository give a complete document."""

    def setUp(self):
        for p in (WORDS_PATH, STRINGS_PATH, FIELDS_PATH, PROTO_DIR):
            if not os.path.exists(os.path.join(REPO, p)):
                self.skipTest(f"{p} is absent")

    def test_each_profile_field_has_words_and_each_key_is_in_the_protocol(self):
        words, strings, fields, proto = load_sources(REPO)
        doc = build_document(words, strings, fields, proto, "0.0.0")
        for f in fields:
            self.assertIn(f["key"], doc["settings"])
        self.assertIn("device_config.active_profile", doc["settings"])
        self.assertEqual(list(doc["targets"]), ["android", "ios", "desktop", "browser", "wear"])
        langs = {i["lang"] for i in doc["settings"]["autococker.fire_mode"]["title"]}
        self.assertEqual(langs, {"en", "de", "es", "es-MX", "es-US", "fr", "fr-CA"})


if __name__ == "__main__":
    unittest.main()
