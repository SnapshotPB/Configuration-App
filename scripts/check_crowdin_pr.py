#!/usr/bin/env python3
"""Stop a Crowdin pull request that replaces a hand-written string.

Translations in this project are written by hand in
``core/resources/src/commonMain/composeResources/values-*/strings.xml``. Crowdin only
manages contributions from outside, and it is not the source of truth. The Crowdin GitHub
integration opens "New Crowdin updates" pull requests from the ``l10n_master`` branch.
Those pull requests replaced hand-written values more than once (#78 to #80): Crowdin
exported an old Spanish text, and it wrote its own copy of the English source file with a
double quote wrapper.

This check compares two commits and reports each string that the second commit changes or
removes. The rules are:

* The English source file ``values/strings.xml`` must not change at all. Crowdin reads it
  and never owns it.
* In a ``values-*/strings.xml`` file, a string that has a value in the base commit must keep
  that value. A change or a removal fails. A new string, or a value for a string that was
  empty, is permitted, because it replaces no hand-written text.
* The pull request must change no other file.

The workflow ``crowdin-guard.yml`` runs this check only on a pull request from a Crowdin
branch. A normal pull request can still change a translation by hand. To accept a Crowdin
text for an existing string, copy it into the repository in a normal pull request.

Usage::

    # Compare the merge commit of a pull request with its base (the default).
    check_crowdin_pr.py

    # Compare two commits. These two hold the Crowdin change of #80.
    check_crowdin_pr.py --base 830b336 --head 3a319b3

The exit code is 0 when the change is safe, 1 when the check finds a problem, and 2 when the
check cannot run.

The pure functions (:func:`parse_strings`, :func:`compare_strings`, :func:`check_changes`)
take their inputs explicitly, so they are testable without git; see
``tests/test_check_crowdin_pr.py``.
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
import xml.etree.ElementTree as ElementTree
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path

RESOURCES_DIR = "core/resources/src/commonMain/composeResources"
SOURCE_FILE = f"{RESOURCES_DIR}/values/strings.xml"
TRANSLATION_FILE = re.compile(rf"^{re.escape(RESOURCES_DIR)}/values-[^/]+/strings\.xml$")
REPO_ROOT = Path(__file__).resolve().parent.parent


class CheckError(RuntimeError):
    """The check cannot read a commit or a file."""


@dataclass(frozen=True)
class Problem:
    """One change that the check does not permit."""

    path: str
    key: str | None
    message: str

    def __str__(self) -> str:
        where = f"{self.path}: {self.key}" if self.key else self.path
        return f"{where}: {self.message}"


def _inner_xml(element: ElementTree.Element) -> str:
    """Return the content of [element] with its attributes other than ``name``.

    ElementTree resolves the entities, so ``&apos;`` and ``'`` give the same value. A
    ``<plurals>`` or ``<string-array>`` element keeps its child elements in the value.
    """
    attributes = sorted((k, v) for k, v in element.attrib.items() if k != "name")
    children = "".join(ElementTree.tostring(child, encoding="unicode") for child in element)
    content = (element.text or "") + children
    return f"{attributes}{content}" if attributes else content


def parse_strings(text: str) -> tuple[dict[str, str], list[str]]:
    """Return the named resources of one ``strings.xml`` and the names that repeat.

    The key is ``<tag>:<name>`` for a tag other than ``string``, so that a ``<plurals>``
    and a ``<string>`` with the same name do not collide.
    """
    try:
        root = ElementTree.fromstring(text)
    except ElementTree.ParseError as error:
        raise CheckError(f"The file is not valid XML: {error}") from error

    values: dict[str, str] = {}
    repeated: list[str] = []
    for element in root:
        name = element.get("name")
        if not isinstance(element.tag, str) or name is None:
            continue
        key = name if element.tag == "string" else f"{element.tag}:{name}"
        if key in values:
            repeated.append(key)
        values[key] = _inner_xml(element)
    return values, repeated


def _shorten(text: str, width: int = 90) -> str:
    flat = " ".join(text.split())
    return flat if len(flat) <= width else f"{flat[: width - 1]}…"


def compare_strings(
    path: str, base_text: str | None, head_text: str | None, *, strict: bool = False
) -> list[Problem]:
    """Return the problems in one ``strings.xml`` file.

    ``None`` means that the file does not exist in that commit. With [strict], every
    difference is a problem, also a new string and a value for an empty string. The check
    uses [strict] for the English source file.
    """
    if head_text is None:
        if base_text is None:
            return []
        return [Problem(path, None, "the pull request deletes the file")]

    try:
        head, repeated = parse_strings(head_text)
    except CheckError as error:
        return [Problem(path, None, str(error))]
    problems = [Problem(path, key, "the name occurs more than once") for key in repeated]

    if base_text is None:
        return problems
    base, _ = parse_strings(base_text)

    for key, old in base.items():
        if not old.strip() and not strict:
            continue
        if key not in head:
            problems.append(Problem(path, key, f"the pull request removes {_shorten(old)!r}"))
        elif head[key] != old:
            problems.append(
                Problem(
                    path,
                    key,
                    f"the pull request replaces {_shorten(old)!r} with {_shorten(head[key])!r}",
                )
            )
    if strict:
        for key in sorted(head.keys() - base.keys()):
            problems.append(Problem(path, key, "the pull request adds a string"))
    return problems


def check_changes(
    changed_paths: list[str],
    read_base: Callable[[str], str | None],
    read_head: Callable[[str], str | None],
) -> list[Problem]:
    """Return every problem in a set of changed files.

    ``read_base`` and ``read_head`` return the text of a file in one commit, or ``None``
    when the file does not exist there.
    """
    problems: list[Problem] = []
    for path in sorted(changed_paths):
        if path == SOURCE_FILE:
            problems.append(
                Problem(path, None, "the pull request changes the English source file")
            )
            problems.extend(
                compare_strings(path, read_base(path), read_head(path), strict=True)
            )
        elif TRANSLATION_FILE.match(path):
            problems.extend(compare_strings(path, read_base(path), read_head(path)))
        else:
            problems.append(
                Problem(path, None, "a Crowdin pull request must change only translation files")
            )
    return problems


def _git(repo: Path, *args: str) -> subprocess.CompletedProcess[bytes]:
    return subprocess.run(["git", *args], cwd=repo, capture_output=True, check=False)


def changed_paths(repo: Path, base: str, head: str) -> list[str]:
    """Return the paths that differ between two commits."""
    result = _git(repo, "diff", "--name-only", "-z", "--no-renames", base, head, "--")
    if result.returncode != 0:
        detail = result.stderr.decode("utf-8", "replace").strip()
        raise CheckError(f"git diff {base} {head} failed: {detail}")
    return [path for path in result.stdout.decode("utf-8").split("\0") if path]


def file_reader(repo: Path, revision: str) -> Callable[[str], str | None]:
    """Return a function that reads a file from [revision], or ``None`` when it is absent."""
    verified = _git(repo, "rev-parse", "--verify", "--quiet", f"{revision}^{{commit}}")
    if verified.returncode != 0:
        raise CheckError(f"{revision} is not a commit in this clone.")

    def read(path: str) -> str | None:
        if _git(repo, "cat-file", "-e", f"{revision}:{path}").returncode != 0:
            return None
        shown = _git(repo, "show", f"{revision}:{path}")
        if shown.returncode != 0:
            detail = shown.stderr.decode("utf-8", "replace").strip()
            raise CheckError(f"git show {revision}:{path} failed: {detail}")
        return shown.stdout.decode("utf-8")

    return read


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description="Fail when a Crowdin pull request replaces a hand-written string.",
    )
    parser.add_argument(
        "--base",
        default="HEAD^1",
        help="Commit before the change (default: HEAD^1, the base of a PR merge commit).",
    )
    parser.add_argument(
        "--head",
        default="HEAD",
        help="Commit after the change (default: HEAD, the PR merge commit).",
    )
    parser.add_argument(
        "--repo",
        type=Path,
        default=REPO_ROOT,
        help="The git work tree (default: the repository that holds this script).",
    )
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        read_base = file_reader(args.repo, args.base)
        read_head = file_reader(args.repo, args.head)
        paths = changed_paths(args.repo, args.base, args.head)
        problems = check_changes(paths, read_base, read_head)
    except CheckError as error:
        print(f"error: {error}", file=sys.stderr)
        return 2

    if not problems:
        print(f"The change touches {len(paths)} file(s) and replaces no string.")
        return 0

    print(f"The change has {len(problems)} problem(s):")
    for problem in problems:
        print(f"  {problem}")
    print(
        "\nThe repository holds the correct translations. Do not merge this pull request."
        "\nPush the repository text to Crowdin with scripts/crowdin_push_translations.py,"
        "\nthen let Crowdin export again. To keep a Crowdin text, copy it into the"
        "\nrepository in a normal pull request."
    )
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
