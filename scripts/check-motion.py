#!/usr/bin/env python3
# Keeps the motion scale from becoming a scale nobody reads.
#
# Motion carries the duration scale, and a duration written at a call site is a
# duration nothing else can reach: it cannot be compared with its neighbours,
# scaled, or found again. Motion names the roles instead, so this scan fails the
# build on a literal duration handed to tween().
#
# Springs are outside what this can check. They carry stiffness and damping
# rather than a duration, and the vocabulary has no spring role to point them at.
#
# Mechanical only. Whether a site picked the RIGHT role is a human question; that
# a site picked a number is not.

from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass
from pathlib import Path

from _modules import source_dirs

ROOT = Path(__file__).resolve().parent.parent

SCAN_EXTS = {".kt"}

EXCLUDE_PATH_FRAGMENTS = ("/build/", "/.gradle/", "/.idea/")

# Where the vocabulary itself lives, and the one place allowed to read the raw
# axis. Anything else asking these questions is bypassing the scale.
VOCABULARY = ("nx-ui/src/desktopMain/kotlin/hivens/ui/theme/Motion.kt",)

# Deliberate exemptions. Each names why the scale cannot reach it -- not a place
# to park a site nobody wanted to move.
EXEMPT = {
    # A state holder, not a composable: a role is a @Composable read and cannot
    # be taken where these defaults are declared, and console scrolling is
    # navigation rather than interface motion.
    "client-ui/src/desktopMain/kotlin/hivens/ui/screens/console/LogScrollState.kt": {"literal-duration"},
    # The easter engine scales its own set pieces through that mirrored value.
    "client-easter": {"literal-duration"},
}


@dataclass(frozen=True)
class Rule:
    key: str
    pattern: re.Pattern[str]
    message: str


RULES = (
    Rule(
        "literal-duration",
        re.compile(r"\btween\s*(?:<[^>]*>)?\s*\(\s*(?:durationMillis\s*=\s*)?\d[\d_]*"),
        "literal duration in tween() -- ask Motion for the role that fits, or Motion.ownRhythm if the period belongs to the effect",
    ),
)


def scan_dirs() -> list[Path]:
    # Derived rather than listed, so a module added tomorrow is covered the day
    # it lands instead of when someone remembers this file.
    return source_dirs(ROOT)


def exempt_keys(rel: str) -> set[str]:
    keys: set[str] = set()
    for prefix, rules in EXEMPT.items():
        if rel == prefix or rel.startswith(prefix + "/"):
            keys |= rules
    return keys


def strip_comment(line: str) -> str:
    # Comments quote the old numbers on purpose when they explain the migration,
    # and KDoc names the axis when documenting what a role does about it.
    body = line.split("//", 1)[0]
    return "" if body.lstrip().startswith(("*", "/*")) else body


def violations() -> list[tuple[str, int, str, str]]:
    found: list[tuple[str, int, str, str]] = []
    for root in scan_dirs():
        for path in sorted(root.rglob("*")):
            if path.suffix not in SCAN_EXTS or not path.is_file():
                continue
            rel = path.relative_to(ROOT).as_posix()
            if any(frag in "/" + rel for frag in EXCLUDE_PATH_FRAGMENTS):
                continue
            if rel in VOCABULARY:
                continue
            allowed = exempt_keys(rel)
            text = path.read_text(encoding="utf-8", errors="replace")
            for n, raw in enumerate(text.splitlines(), start=1):
                line = strip_comment(raw)
                for rule in RULES:
                    if rule.key in allowed:
                        continue
                    if rule.pattern.search(line):
                        found.append((rel, n, rule.key, rule.message))
    return found


def main() -> int:
    parser = argparse.ArgumentParser(description="Motion-scale bypass lint.")
    parser.add_argument("--strict", action="store_true", help="exit non-zero on any finding")
    args = parser.parse_args()

    found = violations()
    for rel, n, key, message in found:
        print(f"{rel}:{n}: [{key}] {message}")

    if not found:
        print("motion: no call site steps around the scale")
        return 0
    print(f"\n{len(found)} site(s) bypass the motion scale")
    return 1 if args.strict else 0


if __name__ == "__main__":
    sys.exit(main())
