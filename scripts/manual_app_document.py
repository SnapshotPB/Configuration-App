#!/usr/bin/env python3
"""Write the application document of the manual.

The manual repository (SnapshotPB-Manual) renders one manual from a firmware document and
an application document. The application document follows the schema
``schemas/app/v2/app.schema.json`` of that repository. It gives, for each setting of the
firmware by its shared key, the words of the app: the title, a description, the path to
the setting in the app, whether the app can change the value, and the platforms that show
it. Each text that the manual prints is localized: an array with one item for each
language, with English required.

This script writes that document from four sources:

1. The strings of the app, ``core/resources/.../values*/strings.xml``, in English and in
   each other locale. They give the titles, the section names, and the screen names, so
   the document shows the words the app shows, in each language of the app.
2. The profile field table, ``core/model/src/jvmTest/resources/profile-fields.json``.
   A test keeps it equal to the ``MarkerProfileField`` enum. It gives the numeric
   profile fields and their sections. A field with no words is an error, so a new field
   in the app needs words before the manual can be built.
3. The protocol, ``core/protocol/builder/Configuration-Protocol``. Each shared key of
   the words file must be a field of the protocol, so a key with a typing error is an
   error.
4. The words file, ``docs/manual/app-words.toml``. A person keeps it. It holds the
   descriptions, the paths, the targets, the topics, and the overrides for one target. A
   text in it is a string in English, or a table with one member for each language.

The version of the app comes from the release tag at HEAD, or from ``--version``. The
release workflow writes the document as the release asset
``snapshotpb-app-<version>-manual-document.json``, so the document of a release travels
with that release, and the manual repository takes it from there.

Usage::

    manual_app_document.py --out ../SnapshotPB-Manual/app/snapshotpb/26.40.1.json
    manual_app_document.py --version 26.40.1 --out app.json
    manual_app_document.py --check          # build the document and write nothing

The pure functions take their inputs as values, so ``tests/test_manual_app_document.py``
tests them with no git and no network.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
import tomllib
import xml.etree.ElementTree as ET

SCHEMA_VERSION = "2.0.0"

# The language of the English text, and the language that each text must have.
DEFAULT_LANG = "en"

# The files of the repository that the document comes from, relative to the root.
RESOURCES_DIR = "core/resources/src/commonMain/composeResources"
STRINGS_PATH = f"{RESOURCES_DIR}/values/strings.xml"
FIELDS_PATH = "core/model/src/jvmTest/resources/profile-fields.json"
PROTO_DIR = "core/protocol/builder/Configuration-Protocol/snapshotpb"
WORDS_PATH = "docs/manual/app-words.toml"

# The scope of a shared key names the protocol message that holds the field.
SCOPE_MESSAGES = {
    "profile": "Profile",
    "autococker": "AutocockerConfig",
    "device_config": "DeviceConfig",
    "device": "Device",
}

# The string id of the section of each profile field group.
GROUP_SECTIONS = {
    "BASIC": "profile_section_basic_function",
    "EYE": "profile_section_eye",
    "TIMING": "profile_section_timing",
    "TRIGGER": "profile_section_trigger",
    "RAMPING": "profile_section_ramping",
}

RELEASE_TAG_RE = re.compile(r"^v(\d+\.\d+\.\d+)$")
LOCALE_DIR_RE = re.compile(r"^values-([a-z]{2,3})(?:-r([A-Z]{2}))?$")
PLACEHOLDER_RE = re.compile(r"\{([a-z_]+)\}")
VERSION_RE = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$")
SHARED_KEY_RE = re.compile(r"^[a-z][a-z0-9_]*\.[a-z][a-z0-9_]*$")


class DocumentError(Exception):
    """A source does not agree with the words file, or a source is not complete."""


# ----- the sources -----


def parse_strings(xml_text: str) -> dict[str, str]:
    """The English strings of the app, by id. An escape of a quote is removed."""
    root = ET.fromstring(xml_text)
    out: dict[str, str] = {}
    for el in root.iter("string"):
        name = el.get("name")
        if name is None:
            continue
        text = "".join(el.itertext())
        out[name] = text.replace("\\'", "'").replace('\\"', '"')
    return out


def locale_tag(dirname: str) -> str | None:
    """The language tag of a resource directory: values-de gives de, values-es-rMX gives
    es-MX, and values gives en. None for a directory that holds no strings."""
    if dirname == "values":
        return DEFAULT_LANG
    m = LOCALE_DIR_RE.match(dirname)
    if not m:
        return None
    return f"{m.group(1)}-{m.group(2)}" if m.group(2) else m.group(1)


def localized(value, owner: str) -> list[dict]:
    """A text of the words file as the schema holds it: a string is English, and a table
    holds one member for each language, with English required."""
    if isinstance(value, str):
        return [{"lang": DEFAULT_LANG, "text": value}]
    if isinstance(value, dict):
        if DEFAULT_LANG not in value:
            raise DocumentError(f"the text of {owner!r} has no English member")
        items = [{"lang": DEFAULT_LANG, "text": value[DEFAULT_LANG]}]
        items += [{"lang": k, "text": v} for k, v in value.items() if k != DEFAULT_LANG]
        return items
    raise DocumentError(f"the text of {owner!r} is not a string or a table")


def parse_fields(json_text: str) -> list[dict]:
    """The numeric profile fields: the shared key of each one and its group."""
    rows = json.loads(json_text)
    out = []
    for row in rows:
        name = row["key"].lower()
        scope = "profile" if name == "fire_rate_cap" else "autococker"
        out.append({"key": f"{scope}.{name}", "field": name, "group": row["group"]})
    return out


def parse_proto_fields(proto_texts: list[str]) -> dict[str, set[str]]:
    """The fields of each protocol message, by message name. A field in a ``oneof``
    belongs to the message around it."""
    re_open = re.compile(r"^\s*(message|enum|oneof)\s+(\w+)\s*\{")
    re_field = re.compile(r"^\s*(?:optional\s+|repeated\s+)?[\w.]+\s+(\w+)\s*=\s*\d+")
    out: dict[str, set[str]] = {}
    for text in proto_texts:
        stack: list[tuple[str, str]] = []
        for raw in text.splitlines():
            line = raw.split("//", 1)[0]
            m = re_open.match(line)
            if m:
                stack.append((m.group(1), m.group(2)))
                out.setdefault(m.group(2), set()) if m.group(1) == "message" else None
                continue
            if line.strip().startswith("}"):
                if stack:
                    stack.pop()
                continue
            m = re_field.match(line)
            if m and stack and stack[-1][0] != "enum":
                message = next((n for k, n in reversed(stack) if k == "message"), None)
                if message is not None:
                    out[message].add(m.group(1))
    return out


def version_from_describe(describe: str) -> str | None:
    """The version of a release tag at HEAD, or None when HEAD is not a release."""
    m = RELEASE_TAG_RE.match(describe.strip())
    return m.group(1) if m else None


def git_version(repo: str) -> str:
    """The version from the release tag at HEAD. An error when HEAD is not a release."""
    try:
        describe = subprocess.run(
            ["git", "-C", repo, "describe", "--tags", "--exact-match", "HEAD"],
            capture_output=True,
            text=True,
            check=True,
        ).stdout
    except (subprocess.CalledProcessError, FileNotFoundError):
        raise DocumentError(
            "HEAD is not at a release tag. Pass --version <MAJOR.MINOR.PATCH>."
        ) from None
    version = version_from_describe(describe)
    if version is None:
        raise DocumentError(f"the tag {describe.strip()!r} is not a release tag")
    return version


# ----- the document -----


def build_document(
    words: dict,
    strings: dict[str, dict[str, str]],
    fields: list[dict],
    proto: dict[str, set[str]],
    version: str,
) -> dict:
    """The application document, as the schema defines it. `strings` holds the strings of
    the app by language tag, with English under en."""
    if not VERSION_RE.match(version):
        raise DocumentError(f"the version {version!r} is not MAJOR.MINOR.PATCH")
    if DEFAULT_LANG not in strings:
        raise DocumentError("the strings of the app have no English set")
    targets = words.get("targets") or {}
    if not targets:
        raise DocumentError("the words file names no target")
    paths = words.get("paths") or {}
    fields_by_key = {f["key"]: f for f in fields}
    settings_words = words.get("settings") or {}

    for key, field in fields_by_key.items():
        if key not in settings_words:
            raise DocumentError(
                f"the profile field {field['field']} has no words: add [settings.\"{key}\"] "
                f"to {WORDS_PATH}"
            )

    settings = {}
    for key, entry in settings_words.items():
        settings[key] = build_setting(key, entry, strings, fields_by_key, proto, targets, paths)

    topics = {}
    for key, entry in (words.get("topics") or {}).items():
        check_shared_key(key)
        if not key.startswith("topic."):
            raise DocumentError(f"the topic key {key!r} must start with topic.")
        topics[key] = build_entry(
            key,
            entry,
            title=localized(require(entry, "title", key), f"{key} title"),
            place={},
            targets=targets,
        )

    app = words.get("app") or {}
    doc = {
        "schema_version": SCHEMA_VERSION,
        "app": without_none(
            {
                "name": localized(require(app, "name", "app"), "app name"),
                "version": version,
                "protocol_version": app.get("protocol_version"),
                "summary": localized(app["summary"], "app summary") if "summary" in app else None,
            }
        ),
        "targets": {
            k: without_none(
                {
                    "name": localized(require(t, "name", f"targets.{k}"), f"targets.{k} name"),
                    "description": localized(t["description"], f"targets.{k} description") if "description" in t else None,
                }
            )
            for k, t in targets.items()
        },
        "settings": settings,
    }
    if topics:
        doc["topics"] = topics
    return doc


def build_setting(
    key: str,
    entry: dict,
    strings: dict[str, dict[str, str]],
    fields_by_key: dict[str, dict],
    proto: dict[str, set[str]],
    targets: dict,
    paths: dict,
) -> dict:
    check_shared_key(key)
    scope, field = key.split(".", 1)
    message = SCOPE_MESSAGES.get(scope)
    if message is None:
        raise DocumentError(f"the key {key!r} has the scope {scope!r}, which no protocol message holds")
    if field not in proto.get(message, set()):
        raise DocumentError(f"the key {key!r} is not a field of the protocol message {message}")

    kind = "profile" if scope in ("profile", "autococker") else "device"
    label_id = entry.get("label") or (f"profile_label_{field}" if kind == "profile" else None)
    if label_id is None:
        raise DocumentError(f"the setting {key!r} needs a label: the string id of its title")
    title = string_in_each_language(strings, label_id, key)

    section_id = None
    if kind == "profile":
        numeric = fields_by_key.get(key)
        section_id = entry.get("section") or (GROUP_SECTIONS[numeric["group"]] if numeric else None)
        if section_id is None:
            raise DocumentError(f"the setting {key!r} needs a section: the string id of its section")
        string(strings, section_id, key)

    place = {}
    for target in targets:
        template = (paths.get(target) or {}).get(kind) or paths.get(kind)
        if template is None:
            raise DocumentError(f"the words file has no [paths] {kind} template")
        place[target] = path_in_each_language(strings, template, label_id, section_id, key)

    return build_entry(key, entry, title=title, place=place, targets=targets)


def build_entry(key: str, entry: dict, title: list[dict], place: dict[str, list[dict]], targets: dict) -> dict:
    """One entry of the document: the words at the top, and the override of each target."""
    out = without_none(
        {
            "title": title,
            "description": localized(require(entry, "description", key), f"{key} description"),
            "where": place.get(next(iter(targets))) if place else (localized(entry["where"], f"{key} where") if "where" in entry else None),
            "editable": entry.get("editable"),
        }
    )
    listed = entry.get("targets")
    overrides = entry.get("overrides") or {}
    for t in list(listed or []) + list(overrides):
        if t not in targets:
            raise DocumentError(f"the entry {key!r} names the target {t!r}, and there is no such target")
    if listed is not None and any(t not in listed for t in overrides):
        raise DocumentError(f"the entry {key!r} overrides a target that its targets list does not name")

    members = list(listed) if listed is not None else list(targets)
    per_target = {}
    for t in members:
        o = {}
        for member, value in (overrides.get(t) or {}).items():
            o[member] = localized(value, f"{key} {t} {member}") if member in ("description", "where") else value
        if place and t in place and place[t] != out.get("where") and "where" not in o:
            o["where"] = place[t]
        per_target[t] = o
    if listed is not None or any(per_target.values()):
        out["targets"] = per_target
    return out


# ----- helpers -----


def check_shared_key(key: str) -> None:
    if not SHARED_KEY_RE.match(key):
        raise DocumentError(f"the key {key!r} is not a shared key <scope>.<name>")


def string(strings: dict[str, dict[str, str]], string_id: str, owner: str) -> str:
    """The English string by id."""
    try:
        return strings[DEFAULT_LANG][string_id]
    except KeyError:
        raise DocumentError(f"the entry {owner!r} names the string {string_id!r}, and strings.xml has no such string") from None


def string_in_each_language(strings: dict[str, dict[str, str]], string_id: str, owner: str) -> list[dict]:
    """A string of the app in each language that has it, with English first."""
    english = string(strings, string_id, owner)
    items = [{"lang": DEFAULT_LANG, "text": english}]
    for lang, table in strings.items():
        if lang != DEFAULT_LANG and string_id in table and table[string_id] != english:
            items.append({"lang": lang, "text": table[string_id]})
    return items


def path_in_each_language(strings: dict[str, dict[str, str]], template: str, label_id: str,
                          section_id: str | None, owner: str) -> list[dict]:
    """A path template filled in each language. `{label}` is the title of the setting,
    `{section}` is its section, and another `{id}` is a string of the app, such as the name
    of a screen. A language with no string for a placeholder uses the English one."""
    ids = PLACEHOLDER_RE.findall(template)
    for i in ids:
        if i not in ("label", "section"):
            string(strings, i, owner)
    if "section" in ids and section_id is None:
        raise DocumentError(f"the path of {owner!r} names a section, and the setting has none")

    def fill(lang: str) -> str:
        table = strings[lang]
        english = strings[DEFAULT_LANG]

        def one(m: re.Match) -> str:
            i = m.group(1)
            sid = label_id if i == "label" else section_id if i == "section" else i
            return table.get(sid, english[sid])

        return PLACEHOLDER_RE.sub(one, template)

    english_path = fill(DEFAULT_LANG)
    items = [{"lang": DEFAULT_LANG, "text": english_path}]
    for lang in strings:
        if lang != DEFAULT_LANG:
            path = fill(lang)
            if path != english_path:
                items.append({"lang": lang, "text": path})
    return items


def require(table: dict, member: str, owner: str):
    try:
        return table[member]
    except KeyError:
        raise DocumentError(f"{owner!r} has no {member!r}") from None


def without_none(d: dict) -> dict:
    return {k: v for k, v in d.items() if v is not None}


def read(path: str) -> str:
    with open(path, encoding="utf-8") as f:
        return f.read()


def load_strings(repo: str) -> dict[str, dict[str, str]]:
    """The strings of the app by language tag: values/ is en, and each values-* is a locale."""
    out = {}
    for name in sorted(os.listdir(os.path.join(repo, RESOURCES_DIR))):
        tag = locale_tag(name)
        path = os.path.join(repo, RESOURCES_DIR, name, "strings.xml")
        if tag is not None and os.path.exists(path):
            out[tag] = parse_strings(read(path))
    if DEFAULT_LANG not in out:
        raise DocumentError(f"{STRINGS_PATH} is absent")
    return out


def load_sources(repo: str) -> tuple[dict, dict[str, dict[str, str]], list[dict], dict[str, set[str]]]:
    words = tomllib.loads(read(os.path.join(repo, WORDS_PATH)))
    strings = load_strings(repo)
    fields = parse_fields(read(os.path.join(repo, FIELDS_PATH)))
    proto_dir = os.path.join(repo, PROTO_DIR)
    texts = []
    for dirpath, _, names in os.walk(proto_dir):
        for n in sorted(names):
            if n.endswith(".proto"):
                texts.append(read(os.path.join(dirpath, n)))
    if not texts:
        raise DocumentError(f"no .proto file under {proto_dir}: run git submodule update --init")
    return words, strings, fields, parse_proto_fields(texts)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Write the application document of the manual.")
    parser.add_argument("--repo", default=os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    parser.add_argument("--version", help="the app version, when HEAD is not at a release tag")
    parser.add_argument("--out", help="the file to write; stdout when absent")
    parser.add_argument("--check", action="store_true", help="build the document and write nothing")
    args = parser.parse_args(argv)
    try:
        words, strings, fields, proto = load_sources(args.repo)
        version = args.version or git_version(args.repo)
        doc = build_document(words, strings, fields, proto, version)
    except (DocumentError, OSError, tomllib.TOMLDecodeError, ET.ParseError, json.JSONDecodeError) as e:
        print(f"error: {e}", file=sys.stderr)
        return 1
    text = json.dumps(doc, indent=2, ensure_ascii=False) + "\n"
    if args.check:
        print(f"ok: {len(doc['settings'])} settings, {len(doc.get('topics', {}))} topics, version {version}")
    elif args.out:
        with open(args.out, "w", encoding="utf-8") as f:
            f.write(text)
        print(f"wrote {args.out}")
    else:
        sys.stdout.write(text)
    return 0


if __name__ == "__main__":
    sys.exit(main())
