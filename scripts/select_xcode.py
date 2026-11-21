#!/usr/bin/env python3
"""Select the newest Xcode release of one major version.

This script examines an Applications folder on a macOS runner.
It selects the newest Xcode release bundle that has the same major version as
the minimum version and is not lower than that minimum.

The script never selects a newer major version, for example Xcode 28.
A change of the major version must be a decision in the workflow, not an effect
of a new runner image.

The script skips symbolic links and beta or RC bundles.
The pure functions (:func:`parse_version`, :func:`xcode_version`,
:func:`select_xcode`) take explicit inputs for unit tests.
See ``tests/test_select_xcode.py``.

Usage::

    select_xcode.py --minimum 27.1
    select_xcode.py --minimum 27.1 --applications /Applications
"""

from __future__ import annotations

import argparse
from collections.abc import Iterable
import os
import re
import sys

# A release bundle name: Xcode_<major>[.<minor>[.<patch>]].app.
XCODE_NAME = re.compile(r"^Xcode_(\d+)(?:\.(\d+))?(?:\.(\d+))?\.app$")

# A version argument: <major>[.<minor>[.<patch>]].
_VERSION_RE = re.compile(r"^(\d+)(?:\.(\d+))?(?:\.(\d+))?$")


def parse_version(text: str) -> tuple[int, int, int]:
    """Parse a version text into a major, minor, and patch tuple.

    Examples: ``"27.1"`` gives ``(27, 1, 0)``, ``"27"`` gives ``(27, 0, 0)``,
    and ``"26.4.1"`` gives ``(26, 4, 1)``.
    This function raises ``ValueError`` for any other text, for example ``""``,
    ``"27.x"``, ``"27.1.2.3"``, or ``"v27"``.
    """
    match = _VERSION_RE.fullmatch(text.strip())
    if match is None:
        raise ValueError(f"Invalid version: {text!r}")
    major = int(match.group(1))
    minor = int(match.group(2)) if match.group(2) is not None else 0
    patch = int(match.group(3)) if match.group(3) is not None else 0
    return major, minor, patch


def xcode_version(name: str) -> tuple[int, int, int] | None:
    """Return the version tuple from a release bundle name, or ``None``.

    ``"Xcode_27.1.app"`` gives ``(27, 1, 0)``.
    This function returns ``None`` for ``"Xcode.app"``, a beta or RC name,
    or any name that ``XCODE_NAME`` does not match.
    """
    match = XCODE_NAME.fullmatch(name)
    if match is None:
        return None
    major = int(match.group(1))
    minor = int(match.group(2)) if match.group(2) is not None else 0
    patch = int(match.group(3)) if match.group(3) is not None else 0
    return major, minor, patch


def select_xcode(names: Iterable[str], minimum: tuple[int, int, int]) -> str | None:
    """Select the newest release bundle name for the major version of ``minimum``.

    Return the name with the highest version that has the same major version as
    ``minimum`` and is not lower than ``minimum``.
    Return ``None`` if no name agrees.
    If two names have the same version, select the shorter name, then the lower
    name in sort order.
    """
    candidates: list[tuple[tuple[int, int, int], str]] = []
    for name in names:
        version = xcode_version(name)
        if version is None:
            continue
        if version[0] != minimum[0]:
            continue
        if version < minimum:
            continue
        candidates.append((version, name))

    if not candidates:
        return None

    _, chosen = min(
        candidates,
        key=lambda item: (-item[0][0], -item[0][1], -item[0][2], len(item[1]), item[1]),
    )
    return chosen


def _format_version(version: tuple[int, int, int]) -> str:
    """Format a version tuple as text."""
    major, minor, patch = version
    if patch == 0:
        return f"{major}.{minor}"
    return f"{major}.{minor}.{patch}"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument(
        "--minimum",
        required=True,
        type=parse_version,
        help="minimum Xcode version, for example 27.1",
    )
    parser.add_argument(
        "--applications",
        default="/Applications",
        help="folder to examine (default: /Applications)",
    )
    args = parser.parse_args(argv)

    applications: str = args.applications
    minimum: tuple[int, int, int] = args.minimum

    try:
        entries = os.listdir(applications)
    except FileNotFoundError:
        print(f"Applications folder does not exist: {applications}", file=sys.stderr)
        return 1
    except NotADirectoryError:
        print(f"Applications path is not a folder: {applications}", file=sys.stderr)
        return 1
    except OSError as error:
        print(f"Cannot read folder {applications}: {error}", file=sys.stderr)
        return 1

    real_entries = [
        name
        for name in entries
        if not os.path.islink(os.path.join(applications, name))
    ]
    selected = select_xcode(real_entries, minimum)
    if selected is not None:
        print(os.path.join(applications, selected))
        return 0

    found_entries = sorted(name for name in entries if name.startswith("Xcode"))
    found_text = ", ".join(found_entries) if found_entries else "none"
    minimum_text = _format_version(minimum)
    print(
        f"No Xcode {minimum[0]}.x release at {minimum_text} or later in {applications}. "
        f"Found: {found_text}",
        file=sys.stderr,
    )
    return 1


if __name__ == "__main__":
    sys.exit(main())
