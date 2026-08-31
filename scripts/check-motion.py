#!/usr/bin/env python3
# Keeps the motion scale from becoming a scale nobody reads.
#
# StyleSpec carries the motion axis, and a duration written at a call site is a
# duration no style can reach: that is how Brut came to declare motion off while
# most of the interface kept animating. Motion names the roles instead, so this
# scan fails the build on the three ways of stepping around it -- a literal
# duration, scaling one by hand, and reading the multiplier to decide whether to
# animate at all.
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
AXIS_OWNERS = VOCABULARY + ("nx-ui/src/desktopMain/kotlin/hivens/ui/theme/StyleSpec.kt",)

# Deliberate exemptions. Each names why the scale cannot reach it -- not a place
# to park a site nobody wanted to move.
EXEMPT = {
    # A state holder, not a composable: LocalStyle does not exist where these
    # defaults are declared, and console scrolling is navigation rather than
    # interface motion.
    "client-ui/src/desktopMain/kotlin/hivens/ui/screens/console/LogScrollState.kt": {"literal-duration"},
    # Mirrors the active style's multiplier into the April Fools engine, which
    # renders outside the theme. This is the bridge that makes it obey.
    "client-ui/src/desktopMain/kotlin/hivens/ui/AppShell.kt": {"reads-multiplier"},
    # The easter engine scales its own set pieces through that mirrored value.
    "client-easter": {"reads-multiplier", "literal-duration", "hand-scaled"},
    # These verify the stillness contract itself, so constructing a style that
    # asks for no motion is the subject under test rather than a bypass of it.
    "nx-ui/src/desktopTest/kotlin/hivens/ui/theme/MotionTest.kt": {"reads-multiplier"},
    "nx-ui/src/desktopTest/kotlin/hivens/ui/nx/NxProgressBarRenderTest.kt": {"reads-multiplier"},
    # The 3D view drives its own scene clock, so it needs the multiplier itself
    # rather than the still/not question -- an idle spin slows with the style
    # before it stops. This is the bridge that hands it over.
    "client-render3d": {"reads-multiplier"},
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
    Rule(
        "hand-scaled",
        re.compile(r"\banimationDurationMs\s*\("),
        "hand-scaled duration -- Motion roles already resolve through the style",
    ),
    Rule(
        "reads-multiplier",
        re.compile(r"\banimationMultiplier\b"),
        "reads the raw motion axis -- use Motion.isStill to ask whether the style wants stillness",
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
                    if rule.key == "hand-scaled" and rel in AXIS_OWNERS:
                        continue
                    if rule.key == "reads-multiplier" and rel in AXIS_OWNERS:
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
