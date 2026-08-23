#!/usr/bin/env python3
# Fails when a module's test suite is not in the pull-request test matrix.
#
# Gradle runs the tasks it is given and says nothing about the ones it is not,
# so a suite left out of tests.yml is a suite that has never run and never
# reported. That is not hypothetical: the widget kernel's default-layout drift
# guard sat red on both branches through several releases because
# :widget-model:test was absent from that line, and the widget loader -- the
# runtime half of the validator the KSP processor enforces at compile time --
# was absent for as long again.
#
# Both directions are checked. A module with tests missing from the workflow is
# a suite nobody runs; a task in the workflow whose module has no tests is a
# rename that will fail the whole step the next time it fires.

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path

from _modules import source_dirs

ROOT = Path(__file__).resolve().parent.parent
WORKFLOW = ROOT / ".github" / "workflows" / "tests.yml"

SOURCE_EXTS = {".kt", ".java"}
# JUnit 4 and 5 both, plus kotlin.test, which is what most of this tree uses.
TEST_MARKER = re.compile(r"^\s*@Test\b", re.MULTILINE)
# `:module:test` / `:experimental:client-boot:test`, as the run line spells them.
TASK_REF = re.compile(r":([A-Za-z0-9\-]+(?::[A-Za-z0-9\-]+)*):(desktopTest|test)\b")

# A test source set, as opposed to main. Covers `test`, `desktopTest`,
# `testFixtures` and the androidTest name Kotlin Multiplatform uses.
def _is_test_source_set(path: Path) -> bool:
    name = path.name
    return name == "test" or name.endswith("Test") or name == "testFixtures"


def module_suites() -> dict[str, tuple[str, int]]:
    """Gradle path -> (task name, test count) for every module that has tests."""
    out: dict[str, tuple[str, int]] = {}
    for module in source_dirs(ROOT):
        rel = module.relative_to(ROOT).as_posix()
        if rel == "buildSrc":
            continue
        src = module / "src"
        sets = [p for p in src.iterdir() if p.is_dir() and _is_test_source_set(p)]
        count = 0
        task = "test"
        for source_set in sets:
            for f in source_set.rglob("*"):
                if f.suffix in SOURCE_EXTS and f.is_file():
                    count += len(TEST_MARKER.findall(f.read_text(encoding="utf-8", errors="replace")))
            # A multiplatform module's suite is desktopTest, not test; asking for
            # the wrong one fails the step rather than skipping quietly.
            if source_set.name == "desktopTest":
                task = "desktopTest"
        if count:
            out[":" + rel.replace("/", ":")] = (task, count)
    return out


def workflow_tasks() -> set[str]:
    if not WORKFLOW.is_file():
        return set()
    text = WORKFLOW.read_text(encoding="utf-8")
    return {f":{m.group(1)}:{m.group(2)}" for m in TASK_REF.finditer(text)}


def main() -> int:
    parser = argparse.ArgumentParser(description="Test-suite coverage gate for the CI matrix.")
    parser.add_argument(
        "--strict",
        action="store_true",
        help="exit 1 if any suite is unrun or any listed task has no tests (CI uses this).",
    )
    args = parser.parse_args()

    suites = module_suites()
    listed = workflow_tasks()

    missing = {p: v for p, v in suites.items() if f"{p}:{v[0]}" not in listed}
    known = {f"{p}:{v[0]}" for p, v in suites.items()}
    stale = sorted(t for t in listed if t not in known)

    if not missing and not stale:
        total = sum(count for _, count in suites.values())
        print(f"check-ci-suites: {len(suites)} module suites, {total} tests, all in the matrix.")
        return 0

    for path, (task, count) in sorted(missing.items()):
        print(f"[unrun] {path}:{task} has {count} test(s) and is not in {WORKFLOW.name}")
    for task in stale:
        print(f"[stale] {task} is listed in {WORKFLOW.name} but that module has no tests")
    return 1 if args.strict else 0


if __name__ == "__main__":
    sys.exit(main())
