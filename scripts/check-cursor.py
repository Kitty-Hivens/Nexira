#!/usr/bin/env python3
# Keeps the pointer the look's to answer.
#
# Compose leaves the pointer to whoever writes a press: its clickable has none, and
# neither has a Material control. A press written with either has no pointer a look
# could answer, and a look that puts a hand over buttons would put it over some. The
# press modifiers of hivens.ui.press are Compose's with the look's pointer, called the
# same and taking the same, so this scan fails the build on Compose's own, on a
# Material control whose modifier asks for no pointer, and on a pointer set past the
# cursor axis.
#
# Mechanical only. Whether a site asks for the RIGHT role is a human question.

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

from _modules import source_dirs

ROOT = Path(__file__).resolve().parent.parent

EXCLUDE_PATH_FRAGMENTS = ("/build/", "/.gradle/", "/.idea/")

TEST_SOURCE_SETS = ("/src/test/", "/src/jvmTest/", "/src/desktopTest/", "/src/commonTest/", "/src/androidUnitTest/")

# Modules outside the launcher, which draw with what they like.
OUTSIDE = ("examples/",)

# Where Compose's own presses are wrapped, and where pointers are answered.
PRESS = "nx-ui/src/jvmSharedMain/kotlin/hivens/ui/press/"
POINTERS = ("look/cursor-api/", "look/cursor-system/", "look/cursor-nx/")

# A pointer that is no look's to answer, each with why.
EXEMPT = {
    # The ghost that is dragged stands in for the pointer, which draws nothing meanwhile.
    "client-ui/src/jvmSharedMain/kotlin/hivens/ui/editor/EditorPointerIcons.kt",
    "client-ui/src/desktopMain/kotlin/hivens/ui/editor/EditorPointerIcons.desktop.kt",
    "client-ui/src/androidMain/kotlin/hivens/ui/editor/EditorPointerIcons.android.kt",
    "client-ui/src/jvmSharedMain/kotlin/hivens/ui/editor/EditorSurfaceHost.kt",
}

FOUNDATION_PRESS = re.compile(r"^import androidx\.compose\.foundation\.(?:(?:selection\.)?(?:clickable|combinedClickable|selectable|toggleable|triStateToggleable))\b")
FOUNDATION_WILDCARD = re.compile(r"^import androidx\.compose\.foundation(?:\.selection)?\.\*")
BARE_PRESS = re.compile(r"\.(clickable|combinedClickable|selectable|toggleable|triStateToggleable)\s*[({]")

# Material's controls that hivens.ui.press wraps whole.
WRAPPED = ("Button", "OutlinedButton", "TextButton", "IconButton")
# The rest, whose modifier has to ask for the pointer itself.
ASKING = (
    "ElevatedButton", "FilledTonalButton", "FilledIconButton", "FilledTonalIconButton", "OutlinedIconButton", "IconToggleButton",
    "Checkbox", "TriStateCheckbox", "RadioButton", "Switch", "FilterChip", "AssistChip", "InputChip", "SuggestionChip", "Tab",
    "LeadingIconTab", "FloatingActionButton", "SmallFloatingActionButton", "ExtendedFloatingActionButton", "DropdownMenuItem",
    "NavigationRailItem", "NavigationBarItem", "SegmentedButton",
)

POINTER = re.compile(r"\bpointerHoverIcon\s*\(|\bimport androidx\.compose\.ui\.input\.pointer\.PointerIcon\b|\bimport java\.awt\.Cursor\b")


def strip_comment(line: str) -> str:
    body = line.split("//", 1)[0]
    return "" if body.lstrip().startswith(("*", "/*")) else body


def calls(text: str, name: str):
    """Every call of [name] in [text], as its offset and the text between its parentheses."""
    for m in re.finditer(r"(?<![\w.@])" + name + r"\(", text):
        before = text[text.rfind("\n", 0, m.start()) + 1 : m.start()]
        if re.search(r"\bfun\s+$", before):
            continue
        depth, i = 1, m.end()
        while i < len(text) and depth:
            depth += {"(": 1, ")": -1}.get(text[i], 0)
            i += 1
        yield m.start(), text[m.end() : i - 1]


def faults(rel: str, source: str) -> list[tuple[int, str]]:
    text = "\n".join(strip_comment(line) for line in source.splitlines())
    lines = text.split("\n")
    out: list[tuple[int, str]] = []

    def line_of(offset: int) -> int:
        return text.count("\n", 0, offset) + 1

    if not rel.startswith(PRESS):
        for n, line in enumerate(lines, start=1):
            if FOUNDATION_PRESS.match(line):
                out.append((n, "Compose's own press: import it from hivens.ui.press, which has the look's pointer"))
            for name in WRAPPED:
                if re.match(r"import androidx\.compose\.material3\." + name + r"\b", line):
                    out.append((n, f"Material's {name}: import it from hivens.ui.press, which has the look's pointer"))
        if any(FOUNDATION_WILDCARD.match(line) for line in lines):
            for n, line in enumerate(lines, start=1):
                m = BARE_PRESS.search(line)
                if m and f"import hivens.ui.press.{m.group(1)}" not in text:
                    out.append((n, f"Compose's own {m.group(1)} through a wildcard: import it from hivens.ui.press"))
        wildcard = "import androidx.compose.material3.*" in text
        if wildcard:
            for name in WRAPPED:
                if f"import hivens.ui.press.{name}\n" in text:
                    continue
                for offset, _ in calls(text, name):
                    out.append((line_of(offset), f"Material's {name} through a wildcard: import it from hivens.ui.press"))
        for name in ASKING:
            if not (wildcard or f"import androidx.compose.material3.{name}\n" in text) or re.search(r"\bfun\s+" + name + r"\(", text):
                continue
            for offset, args in calls(text, name):
                if "ursor(" in args or re.search(r"onCheckedChange\s*=\s*null", args):
                    continue
                out.append((line_of(offset), f"Material's {name} asks for no pointer: give its modifier pressCursor()"))
    if not rel.startswith(POINTERS) and rel not in EXEMPT:
        for n, line in enumerate(lines, start=1):
            if POINTER.search(line):
                out.append((n, "a pointer set past the cursor axis: ask it by role with Modifier.cursor"))
    return sorted(out)


def violations() -> list[tuple[str, int, str]]:
    found: list[tuple[str, int, str]] = []
    for root in source_dirs(ROOT):
        for path in sorted(root.rglob("*.kt")):
            rel = path.relative_to(ROOT).as_posix()
            if any(frag in "/" + rel for frag in EXCLUDE_PATH_FRAGMENTS + TEST_SOURCE_SETS) or rel.startswith(OUTSIDE):
                continue
            found += [(rel, n, message) for n, message in faults(rel, path.read_text(encoding="utf-8", errors="replace"))]
    return found


def main() -> int:
    parser = argparse.ArgumentParser(description="Pointers set past the cursor axis.")
    parser.add_argument("--strict", action="store_true", help="exit non-zero on any finding")
    args = parser.parse_args()

    found = violations()
    for rel, n, message in found:
        print(f"{rel}:{n}: {message}")
    if not found:
        print("cursor: every press and every pointer is asked of the look")
        return 0
    print(f"\n{len(found)} site(s) step around the cursor axis")
    return 1 if args.strict else 0


if __name__ == "__main__":
    sys.exit(main())
