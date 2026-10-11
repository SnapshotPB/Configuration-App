#!/usr/bin/env python3
"""Push the hand-written translations from this repository up to Crowdin.

Translations in this project are written by hand in
``core/resources/src/commonMain/composeResources/values-*/strings.xml``. Crowdin only
manages contributions from outside, so the repository holds the correct text. Crowdin
does not read those files back. When Crowdin's own copy of a value is older than the
repository's, Crowdin exports its copy on every sync and opens a "New Crowdin updates"
pull request that reverts the repository. A git revert cannot stop that loop, because it
changes the repository and never changes Crowdin. The check ``check_crowdin_pr.py`` stops
such a pull request, and this script removes the cause.

The script reads each value from the repository and compares it with the value Crowdin
exports.

Crowdin keeps more than one translation per string per language. It exports the approved
one. When no translation is approved, it exports the newest one. The correct text is often
already in Crowdin and loses on age to a newer suggestion from translation memory. The
script therefore approves the translation that matches the repository when one exists, and
adds a translation only when none matches. An approved value cannot lose to a newer
suggestion, so the next export agrees with ``master``.

The script reports and changes nothing until you add ``--apply``. Run it once without the
flag, read the comparison, then run it again with the flag.

Usage::

    # Report only. Compare every string in every language that crowdin.yml maps.
    CROWDIN_TOKEN=... crowdin_push_translations.py --all

    # Write and approve every value that differs.
    CROWDIN_TOKEN=... crowdin_push_translations.py --all --apply

    # Work on some strings in one language.
    crowdin_push_translations.py --token-file ~/crowdin.token --language es-MX \\
        profile_label_dwell profile_label_delay

    # Crowdin Enterprise.
    crowdin_push_translations.py --all --base-url https://acme.api.crowdin.com/api/v2

The token is a credential. It can read and change every translation in the project, so
give it access to this one project only, with project read and write, and revoke it when you
finish. The script reads it from the file that ``--token-file`` names, or else from
``$CROWDIN_TOKEN``. It never prints the token and never writes it to disk.

The locale map comes from ``crowdin.yml``, so the script and the Crowdin project cannot
disagree about which directory belongs to which language.

The pure functions (:func:`parse_language_map`, :func:`read_strings`,
:func:`index_strings`, :func:`effective_translation`, :func:`decide`,
:func:`plan_changes`) take their inputs explicitly, so they are testable without a
network; see ``tests/test_crowdin_push_translations.py``.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ElementTree
from dataclasses import dataclass
from pathlib import Path

DEFAULT_BASE_URL = "https://api.crowdin.com/api/v2"
REPO_ROOT = Path(__file__).resolve().parent.parent
CROWDIN_CONFIG = REPO_ROOT / "crowdin.yml"
RESOURCES_DIR = REPO_ROOT / "core/resources/src/commonMain/composeResources"
SOURCE_STRINGS = RESOURCES_DIR / "values" / "strings.xml"

# Crowdin API v2 returns at most 500 records in one page.
PAGE_SIZE = 500


class CrowdinError(RuntimeError):
    """The API refused a request, or answered with something this script cannot use."""


@dataclass(frozen=True)
class Change:
    """One value that Crowdin exports and the repository disagrees with."""

    key: str
    language_id: str
    directory: str
    repo_text: str
    crowdin_text: str | None
    string_id: int
    action: str
    translation_id: int | None

    @property
    def is_absent(self) -> bool:
        """Reports whether Crowdin exports no translation at all for this language."""
        return self.crowdin_text is None


def effective_translation(
    translations: list[dict], approved_ids: set[int]
) -> dict | None:
    """Return the translation Crowdin exports for one language.

    Crowdin exports an approved translation. With no approval it exports the newest one,
    which is why a stale suggestion added after the correct text wins every sync.
    """
    if not translations:
        return None
    approved = [item for item in translations if item["id"] in approved_ids]
    pool = approved or translations
    return max(pool, key=lambda item: item["id"])


def decide(
    repo_text: str, translations: list[dict], approved_ids: set[int]
) -> tuple[str, int | None, str | None]:
    """Choose what to do for one language.

    Returns the action, the translation to approve, and the text Crowdin exports now. The
    action is ``approve`` when Crowdin already holds the repository text, ``add`` when it
    holds no such text, and ``none`` when the repository text is already approved.
    """
    current = effective_translation(translations, approved_ids)
    current_text = current["text"] if current else None

    matches = [item for item in translations if item["text"] == repo_text]
    if not matches:
        return ("add", None, current_text)

    best = max(matches, key=lambda item: item["id"])
    if best["id"] in approved_ids:
        return ("none", best["id"], current_text)
    return ("approve", best["id"], current_text)


def parse_language_map(config_text: str) -> dict[str, str]:
    """Extract the Crowdin-language-to-directory map from ``crowdin.yml``.

    The file holds one ``languages_mapping.android_code`` block, and every line in it maps
    a Crowdin language id to the qualifier this repository uses in a directory name, for
    example ``es-MX: es-rMX``. A full YAML parser would add a dependency for one block, so
    this reads the block directly and fails loudly when the shape is not what it expects.
    """
    marker = re.search(r"^\s*android_code:\s*$", config_text, re.MULTILINE)
    if marker is None:
        raise CrowdinError("crowdin.yml has no languages_mapping.android_code block.")

    mapping: dict[str, str] = {}
    entry = re.compile(r"^(\s+)([A-Za-z0-9-]+):\s*([A-Za-z0-9-]+)\s*$")
    indent: int | None = None
    for line in config_text[marker.end():].splitlines():
        if not line.strip():
            continue
        match = entry.match(line)
        if match is None:
            break
        if indent is None:
            indent = len(match.group(1))
        elif len(match.group(1)) != indent:
            break
        mapping[match.group(2)] = match.group(3)

    if not mapping:
        raise CrowdinError("crowdin.yml maps no languages.")
    return mapping


def read_strings(strings_xml: Path) -> dict[str, str]:
    """Return the text of every ``<string>`` element in one file, by name.

    ElementTree resolves the XML entities, so each value is the text a translator sees.
    The result keeps the order of the file.
    """
    try:
        root = ElementTree.parse(strings_xml).getroot()
    except (OSError, ElementTree.ParseError) as error:
        raise CrowdinError(f"{strings_xml}: {error}") from error

    values: dict[str, str] = {}
    for element in root.iter("string"):
        name = element.get("name")
        if name is not None:
            values[name] = "".join(element.itertext())
    return values


def index_strings(records: list[dict], keys: list[str]) -> tuple[dict[str, dict], list[str]]:
    """Find the Crowdin source string for each key.

    Returns the strings that were found and the keys that Crowdin does not hold yet, for
    example a string that the repository added after the last Crowdin sync. A key that
    occurs in more than one Crowdin file is an error, because the script cannot know which
    one the repository file belongs to.
    """
    by_key: dict[str, list[dict]] = {}
    for record in records:
        by_key.setdefault(record.get("identifier", ""), []).append(record)

    found: dict[str, dict] = {}
    missing: list[str] = []
    for key in keys:
        matches = by_key.get(key, [])
        if not matches:
            missing.append(key)
        elif len(matches) > 1:
            files = ", ".join(str(match.get("fileId")) for match in matches)
            raise CrowdinError(f"{key} appears in more than one file (ids {files}).")
        else:
            found[key] = matches[0]
    return found, missing


def plan_changes(
    key: str,
    string_id: int,
    repo_values: dict[str, str],
    crowdin_state: dict[str, tuple[list[dict], set[int]]],
    directories: dict[str, str],
) -> list[Change]:
    """Return one :class:`Change` per language that Crowdin does not yet export correctly.

    ``crowdin_state`` maps a language to its translations and to the ids that carry an
    approval. A language whose repository text is already the approved one produces
    nothing, so a second run with ``--apply`` sends no request and stays safe to repeat.
    """
    changes = []
    for language_id, repo_text in sorted(repo_values.items()):
        translations, approved_ids = crowdin_state.get(language_id, ([], set()))
        action, translation_id, crowdin_text = decide(repo_text, translations, approved_ids)
        if action == "none":
            continue
        changes.append(
            Change(
                key=key,
                language_id=language_id,
                directory=directories[language_id],
                repo_text=repo_text,
                crowdin_text=crowdin_text,
                string_id=string_id,
                action=action,
                translation_id=translation_id,
            )
        )
    return changes


class CrowdinClient:
    """A thin client over the parts of Crowdin API v2 this script needs."""

    def __init__(self, token: str, base_url: str = DEFAULT_BASE_URL) -> None:
        self._token = token
        self._base_url = base_url.rstrip("/")

    def _request(self, method: str, path: str, body: dict | None = None) -> dict:
        url = f"{self._base_url}{path}"
        data = json.dumps(body).encode("utf-8") if body is not None else None
        request = urllib.request.Request(url, data=data, method=method)
        request.add_header("Authorization", f"Bearer {self._token}")
        request.add_header("Content-Type", "application/json")
        try:
            with urllib.request.urlopen(request, timeout=60) as response:
                payload = response.read().decode("utf-8")
        except urllib.error.HTTPError as error:
            detail = error.read().decode("utf-8", "replace")[:600]
            raise CrowdinError(f"{method} {path} answered {error.code}: {detail}") from error
        except urllib.error.URLError as error:
            raise CrowdinError(f"{method} {path} did not reach Crowdin: {error.reason}") from error
        return json.loads(payload) if payload else {}

    def _list(self, path: str, params: dict | None = None) -> list[dict]:
        """Return every record of a list endpoint, one page after the other."""
        records: list[dict] = []
        offset = 0
        while True:
            query = urllib.parse.urlencode({**(params or {}), "limit": PAGE_SIZE, "offset": offset})
            page = [item["data"] for item in self._request("GET", f"{path}?{query}").get("data", [])]
            records.extend(page)
            if len(page) < PAGE_SIZE:
                return records
            offset += PAGE_SIZE

    def list_projects(self) -> list[dict]:
        return self._list("/projects")

    def get_project(self, project_id: int) -> dict:
        return self._request("GET", f"/projects/{project_id}")["data"]

    def list_strings(self, project_id: int) -> list[dict]:
        """Return every source string in the project."""
        return self._list(f"/projects/{project_id}/strings")

    def list_translations(self, project_id: int, string_id: int, language_id: str) -> list[dict]:
        """Return every translation Crowdin holds for one string in one language."""
        return self._list(
            f"/projects/{project_id}/translations",
            {"stringId": string_id, "languageId": language_id},
        )

    def list_approved_ids(self, project_id: int, string_id: int, language_id: str) -> set[int]:
        """Return the ids of the translations that carry an approval."""
        approvals = self._list(
            f"/projects/{project_id}/approvals",
            {"stringId": string_id, "languageId": language_id},
        )
        return {int(item["translationId"]) for item in approvals}

    def get_state(
        self, project_id: int, string_id: int, language_id: str
    ) -> tuple[list[dict], set[int]]:
        """Return the translations and the approved ids for one language."""
        return (
            self.list_translations(project_id, string_id, language_id),
            self.list_approved_ids(project_id, string_id, language_id),
        )

    def add_translation(self, project_id: int, string_id: int, language_id: str, text: str) -> dict:
        body = {"stringId": string_id, "languageId": language_id, "text": text}
        return self._request("POST", f"/projects/{project_id}/translations", body)["data"]

    def approve(self, project_id: int, translation_id: int) -> dict:
        body = {"translationId": translation_id}
        return self._request("POST", f"/projects/{project_id}/approvals", body)["data"]


def load_token(token_file: Path | None, environ: dict[str, str] | None = None) -> str:
    """Read the API token from [token_file], or else from ``$CROWDIN_TOKEN``.

    An explicit file wins over the environment. The token is never printed.
    """
    if token_file is not None:
        try:
            token = token_file.read_text(encoding="utf-8").strip()
        except OSError as error:
            raise CrowdinError(f"Cannot read the token file: {error}") from error
        if not token:
            raise CrowdinError(f"{token_file} is empty.")
        return token

    from_env = (os.environ if environ is None else environ).get("CROWDIN_TOKEN", "").strip()
    if from_env:
        return from_env
    raise CrowdinError("No token. Set CROWDIN_TOKEN or pass --token-file.")


def choose_project(projects: list[dict], selector: str | None) -> dict:
    """Pick the project by id, identifier or name, or explain the choice to the user."""
    if selector:
        for project in projects:
            values = {
                str(project.get("id")),
                str(project.get("identifier", "")),
                str(project.get("name", "")),
            }
            if selector in values:
                return project
        raise CrowdinError(f"No project matches {selector!r}.")
    if len(projects) == 1:
        return projects[0]
    names = "\n".join(
        f"  {project.get('id')}  {project.get('identifier')}  {project.get('name')}"
        for project in projects
    )
    raise CrowdinError(f"The token reaches more than one project. Pass --project.\n{names}")


def shorten(text: str | None, width: int = 110) -> str:
    """Return a one-line form of [text] for the report."""
    if text is None:
        return "(no translation)"
    flat = " ".join(text.split())
    return flat if len(flat) <= width else f"{flat[: width - 1]}…"


def report_settings(project: dict) -> None:
    """Print the project settings that control whether the old text can come back.

    Crowdin does not expose its version-control integration in the public API, so the
    switch that stops Crowdin from writing translation files into the repository is not
    reachable from here. Change that one in the web interface.
    """
    print("\nProject settings that bear on this problem:")
    for field in ("translateDuplicates", "autoSubstitution", "useGlobalTm", "qaCheckIsActive"):
        if field in project:
            print(f"  {field}: {project[field]}")
    print(
        "\n  The translation-memory pre-translation automation and the GitHub integration\n"
        "  are not in the public API. Set both in the Crowdin web interface:\n"
        "    1. Turn off pre-translation from translation memory on sync, so it cannot\n"
        "       refill an approved value with an older one.\n"
        "    2. Turn off the push of the source file to the repository. Crowdin wrote its\n"
        "       own copy of values/strings.xml, with a double quote wrapper, in #80."
    )


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Push hand-written translations from this repository up to Crowdin.",
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("keys", nargs="*", help="String names to push.")
    parser.add_argument(
        "--all",
        action="store_true",
        help="Push every string in the English values/strings.xml.",
    )
    parser.add_argument(
        "--apply",
        action="store_true",
        help="Write and approve the values. Without this flag the script only reports.",
    )
    parser.add_argument(
        "--language",
        action="append",
        dest="languages",
        metavar="ID",
        help="Crowdin language id, repeatable (default: every language in crowdin.yml).",
    )
    parser.add_argument("--project", help="Project id, identifier or name.")
    parser.add_argument("--base-url", default=DEFAULT_BASE_URL, help="API root for Enterprise.")
    parser.add_argument(
        "--token-file",
        type=Path,
        help="File that holds the API token. Without it, the script reads $CROWDIN_TOKEN.",
    )
    return parser


def select_keys(args: argparse.Namespace, source_keys: list[str]) -> list[str]:
    """Return the keys to push, from ``--all`` or from the named keys."""
    if args.all and args.keys:
        raise CrowdinError("Pass --all or name keys, not both.")
    if args.all:
        return list(source_keys)
    if not args.keys:
        raise CrowdinError("Name the keys to push, or pass --all.")
    unknown = [key for key in args.keys if key not in source_keys]
    if unknown:
        raise CrowdinError(f"values/strings.xml has no string named {', '.join(unknown)}.")
    return list(args.keys)


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)

    try:
        directories = parse_language_map(CROWDIN_CONFIG.read_text(encoding="utf-8"))
        keys = select_keys(args, list(read_strings(SOURCE_STRINGS)))
        languages = args.languages or sorted(directories)
        unknown = [language for language in languages if language not in directories]
        if unknown:
            raise CrowdinError(f"crowdin.yml does not map {', '.join(unknown)}.")
        repo_files = {
            language_id: read_strings(
                RESOURCES_DIR / f"values-{directories[language_id]}" / "strings.xml"
            )
            for language_id in languages
        }
        token = load_token(args.token_file)
    except (CrowdinError, OSError) as error:
        print(f"error: {error}", file=sys.stderr)
        return 2

    client = CrowdinClient(token, args.base_url)
    try:
        project = choose_project(client.list_projects(), args.project)
        project_id = int(project["id"])
        print(f"Project {project_id}  {project.get('identifier')}  {project.get('name')}")
        print(f"{len(keys)} string(s), languages {', '.join(languages)}")

        sources, not_in_crowdin = index_strings(client.list_strings(project_id), keys)
        for key in not_in_crowdin:
            print(f"\n{key}: Crowdin does not hold this string yet. Skipped.")

        all_changes: list[Change] = []
        for key, source in sources.items():
            string_id = int(source["id"])
            repo_values = {
                language_id: values[key]
                for language_id, values in repo_files.items()
                if key in values
            }
            crowdin_state = {
                language_id: client.get_state(project_id, string_id, language_id)
                for language_id in repo_values
            }
            changes = plan_changes(key, string_id, repo_values, crowdin_state, directories)
            absent = [language for language in languages if language not in repo_values]
            if not changes and not absent:
                continue

            print(f"\n{key}  (string {string_id})")
            print(f"  source:  {shorten(source.get('text'))}")
            for language_id in absent:
                print(f"  {language_id} [skipped]  the repository has no value")
            for change in changes:
                translations, _ = crowdin_state[change.language_id]
                print(f"  {change.language_id} [{change.action}]  {len(translations)} translation(s)")
                print(f"    crowdin exports: {shorten(change.crowdin_text)}")
                print(f"    repo:            {shorten(change.repo_text)}")
            all_changes.extend(changes)

        if not all_changes:
            print("\nCrowdin already exports the repository text. It needs no change.")
            report_settings(client.get_project(project_id))
            return 0

        print(f"\n{len(all_changes)} value(s) need a change.")
        if not args.apply:
            print("Report only. Add --apply to make them.")
            report_settings(client.get_project(project_id))
            return 0

        for change in all_changes:
            if change.action == "approve":
                # Crowdin already holds the correct text. It loses to a newer suggestion
                # only because nothing is approved, so an approval alone settles it.
                translation_id = int(change.translation_id or 0)
                approval = client.approve(project_id, translation_id)
                print(
                    f"  {change.key} {change.language_id}: approved the translation "
                    f"{translation_id} that was already there (approval {approval['id']})"
                )
            else:
                created = client.add_translation(
                    project_id, change.string_id, change.language_id, change.repo_text
                )
                approval = client.approve(project_id, int(created["id"]))
                print(
                    f"  {change.key} {change.language_id}: wrote translation "
                    f"{created['id']}, approval {approval['id']}"
                )

        print("\nVerify:")
        exit_code = 0
        for change in all_changes:
            translations, approved_ids = client.get_state(
                project_id, change.string_id, change.language_id
            )
            now = effective_translation(translations, approved_ids)
            ok = now is not None and now["text"] == change.repo_text and now["id"] in approved_ids
            print(f"  {change.key} {change.language_id}: {'ok, approved' if ok else 'MISMATCH'}")
            if not ok:
                exit_code = 1

        report_settings(client.get_project(project_id))
        return exit_code
    except CrowdinError as error:
        print(f"error: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
