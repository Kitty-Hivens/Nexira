#!/usr/bin/env python3
# Fails when a user-facing string is hardcoded in Compose UI code instead of
# going through AppStrings / LocalStrings.
#
# Why this exists: AppStrings enforces COMPLETENESS -- the Kotlin compiler
# requires every key to be implemented in en/ru/de, so a missing translation
# does not build. It does NOT enforce USAGE: nothing stops a literal from
# bypassing LocalStrings entirely. Compose Desktop has no Android-style
# HardcodedText lint, so without this gate a hardcoded string sails through
# the compiler, the tests, and review unnoticed. This is the call-site guard.
#
# High-precision heuristic: a Cyrillic run inside a string literal in Compose UI
# code is almost always a hardcoded RU UI string. The locale files
# and build output are excluded by path; comments may carry Cyrillic notes
# and are skipped; and the @PropLabel / @Widget(...) / displayName annotation
# ARGUMENT is stripped before the scan -- the key it carries is a compile-time
# constant resolved through LocalStrings elsewhere, not user-facing text. Only
# the annotation call is removed: the rest of the line is still scanned, so a
# hardcoded Cyrillic prop default sitting beside the annotation
# (`@PropLabel("k") val title: String = "<cyrillic>"`) is caught rather than
# masked by the annotation. English literals are a lower-precision second
# layer and are left to a future allowlist-backed rule.
#
# An "// i18n-allow" marker anywhere on a line exempts that line -- the escape
# hatch for a genuinely non-localizable Cyrillic literal (none today) and for
# the rare trailing-comment false positive the line-start comment skip misses.

from __future__ import annotations

import argparse
import re
import sys
from dataclasses import dataclass
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SCAN_EXT = ".kt"
EXCLUDE_DIR_PARTS = {"build", "i18n"}

# Which modules count as Compose UI is asked of the build file, not kept as a
# list here: the heuristic below is only sound where Compose is, and a list is
# how nx-ui went unscanned after it was split out of client-ui. Any module whose
# build applies a Compose plugin or depends on the runtime qualifies, so the next
# UI module is covered the day it lands.
COMPOSE_MARKERS = ("plugins.compose", "org.jetbrains.compose", "compose.runtime")

# Main source sets only. A render test hardcodes labels on purpose -- the rule is
# about what ships, and a test fixture is not user-facing text.
def _is_main_source_set(path: Path) -> bool:
    return path.name == "main" or path.name.endswith("Main")


def scan_roots() -> list[Path]:
    roots: list[Path] = []
    for module in sorted(ROOT.iterdir()):
        build_file = module / "build.gradle.kts"
        src = module / "src"
        if not src.is_dir() or not build_file.is_file():
            continue
        try:
            build_text = build_file.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        if not any(marker in build_text for marker in COMPOSE_MARKERS):
            continue
        roots.extend(p for p in sorted(src.iterdir()) if p.is_dir() and _is_main_source_set(p))
    return roots

# An i18n-allow marker on a line exempts it (escape hatch / false-positive
# override). Convention: put it in a trailing comment -- "// i18n-allow".
ALLOW_MARKER = "i18n-allow"

# A string literal containing at least one Cyrillic code point. The boundary
# chars (U+0400..U+04FF, the Russian alphabet plus YO) are built with chr()
# so this file stays strictly ASCII -- no literal Cyrillic and no backslash-u
# in source.
_CYRILLIC_CLASS = "[" + chr(0x0400) + "-" + chr(0x04FF) + "]"
CYRILLIC_LITERAL = re.compile('"[^"]*' + _CYRILLIC_CLASS + '[^"]*"')

# Whole-line comment (// or KDoc * / block-open). Cyrillic inside a comment is
# a developer note, not user-facing text.
COMMENT_LINE = re.compile(r"^\s*(?://|\*|/\*)")

# Annotation argument: the key it carries is a compile-time constant resolved
# through LocalStrings elsewhere. Stripped (not skipped) so the remainder of
# the line -- e.g. a prop default value beside the annotation -- is still
# scanned. The argument list is a simple key / range with no nested ')', so a
# non-greedy `[^)]*` is enough here.
ANNOTATION_STRIP = re.compile(r'@\w+\s*\([^)]*\)|\bdisplayName\s*=\s*"[^"]*"')


@dataclass
class Hit:
    path: Path
    line_no: int
    line: str


def scan_file(path: Path) -> list[Hit]:
    hits: list[Hit] = []
    try:
        with path.open("r", encoding="utf-8", errors="replace") as fh:
            for line_no, line in enumerate(fh, start=1):
                if ALLOW_MARKER in line:
                    continue
                if COMMENT_LINE.match(line):
                    continue
                scanned = ANNOTATION_STRIP.sub("", line)
                if CYRILLIC_LITERAL.search(scanned):
                    hits.append(Hit(path=path, line_no=line_no, line=line.rstrip()))
    except OSError as e:
        print(f"warn: cannot read {path}: {e}", file=sys.stderr)
    return hits


def _excluded(path: Path) -> bool:
    # Dir-part membership, not substring -- portable across separators
    # (POSIX "/i18n/" vs Windows "\\i18n\\").
    return any(part in EXCLUDE_DIR_PARTS for part in path.parts)


def walk_targets() -> list[Path]:
    out: list[Path] = []
    for root in scan_roots():
        out.extend(p for p in root.rglob("*" + SCAN_EXT) if not _excluded(p))
    return out


def main() -> int:
    parser = argparse.ArgumentParser(description="i18n hardcoded-string gate (Compose UI).")
    parser.add_argument(
        "--strict",
        action="store_true",
        help="exit 1 if any hits found (CI uses this; baseline is zero).",
    )
    parser.add_argument(
        "paths",
        nargs="*",
        type=Path,
        help="restrict scan to these paths (default: every Compose module's main sources).",
    )
    args = parser.parse_args()

    if args.paths:
        targets: list[Path] = []
        for p in args.paths:
            p = p if p.is_absolute() else (Path.cwd() / p).resolve()
            if p.is_file() and p.suffix == SCAN_EXT:
                targets.append(p)
            elif p.is_dir():
                targets.extend(
                    sub for sub in p.rglob("*" + SCAN_EXT) if not _excluded(sub)
                )
    else:
        targets = walk_targets()

    hits: list[Hit] = []
    for path in targets:
        hits.extend(scan_file(path))

    if not hits:
        print(f"check-i18n: 0 hardcoded UI strings across {len(targets)} files.")
        return 0

    print(f"check-i18n: {len(hits)} hardcoded UI string(s) across {len(targets)} files.")
    print("Route each through LocalStrings: add a key to AppStrings + en/ru/de, render s.key.")
    print()
    for hit in hits:
        rel = hit.path.relative_to(ROOT) if hit.path.is_relative_to(ROOT) else hit.path
        print(f"  {rel}:{hit.line_no}: {hit.line.strip()[:120]}")
    print()

    return 1 if args.strict else 0


if __name__ == "__main__":
    sys.exit(main())
