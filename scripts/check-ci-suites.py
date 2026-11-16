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
#
# The look modules are named once, by the aggregate task their parent project
# registers over all of them (see AGGREGATES). A look module counts as run when
# the workflow names that aggregate and its tests sit where the `test` task the
# aggregate depends on runs them. Tests anywhere else in a look module are still
# a suite nobody runs.

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
# `:module:test` / `:experimental:client-boot:test`, a whole word of the command.
TASK_WORD = re.compile(r":[A-Za-z0-9\-]+(?::[A-Za-z0-9\-]+)*:(?:desktopTest|test)")


class Aggregate:
    """A parent project's task over the `test` task of every module under it.

    [parent] is its Gradle path, [task] the task's name, [build_file] the parent's
    build script, which has to register the task over its subprojects for the
    workflow's mention of it to run anything, and [covered] the source sets whose
    tests that `test` task runs: a multiplatform module's JVM target runs its
    jvmTest and its commonTest, and nothing else.

    The build script is held to one statement, the one that makes the task depend
    on the `test` task of every module under the parent, inside the registration of
    the task itself and outside any comment. A registration that does anything less
    runs fewer suites than the workflow promises.
    """

    DEPENDS = 'dependsOn(subprojects.map { "${it.path}:test" })'


    def __init__(self, parent: str, task: str, build_file: Path, covered: frozenset[str]):
        self.parent = parent
        self.task = task
        self.build_file = build_file
        self.covered = covered

    @property
    def ref(self) -> str:
        return f"{self.parent}:{self.task}"

    def holds(self, gradle_path: str) -> bool:
        return gradle_path.startswith(self.parent + ":")

    def registered(self) -> bool:
        if not self.build_file.is_file():
            return False
        return registers_over_subprojects(self.build_file.read_text(encoding="utf-8"), self.task, self.DEPENDS)


def _without_comments(kotlin: str) -> str:
    """[kotlin] with its comments blanked out, string literals left as they are."""
    out: list[str] = []
    i, n = 0, len(kotlin)
    while i < n:
        if kotlin.startswith('"', i):
            end = i + 1
            while end < n and kotlin[end] != '"':
                end += 2 if kotlin[end] == "\\" else 1
            out.append(kotlin[i:end + 1])
            i = end + 1
        elif kotlin.startswith("//", i):
            end = kotlin.find("\n", i)
            i = n if end < 0 else end
        elif kotlin.startswith("/*", i):
            end = kotlin.find("*/", i + 2)
            i = n if end < 0 else end + 2
            out.append(" ")
        else:
            out.append(kotlin[i])
            i += 1
    return "".join(out)


def registers_over_subprojects(script: str, task: str, depends: str) -> bool:
    """Whether [script] registers [task] with [depends] as a statement of its body, outside comments."""
    code = _without_comments(script)
    match = re.search(r'tasks\.register\(\s*"' + re.escape(task) + r'"\s*\)\s*\{', code)
    if match is None:
        return False
    depth, start = 1, match.end()
    for i in range(start, len(code)):
        if code[i] == "{":
            depth += 1
        elif code[i] == "}":
            depth -= 1
            if depth == 0:
                body = code[start:i]
                return re.sub(r"\s+", " ", depends) in re.sub(r"\s+", " ", body)
    return False


AGGREGATES = [
    Aggregate(":look", "allTests", ROOT / "look" / "build.gradle.kts", frozenset({"jvmTest", "commonTest"})),
]

# A test source set, as opposed to main. Covers `test`, `desktopTest`,
# `testFixtures` and the androidTest name Kotlin Multiplatform uses.
def _is_test_source_set(path: Path) -> bool:
    name = path.name
    return name == "test" or name.endswith("Test") or name == "testFixtures"


def test_counts(module: Path) -> dict[str, int]:
    """Test source set name -> test count, for the sets of [module] that hold any."""
    out: dict[str, int] = {}
    for source_set in (p for p in (module / "src").iterdir() if p.is_dir() and _is_test_source_set(p)):
        count = 0
        for f in source_set.rglob("*"):
            if f.suffix in SOURCE_EXTS and f.is_file():
                count += len(TEST_MARKER.findall(f.read_text(encoding="utf-8", errors="replace")))
        if count:
            out[source_set.name] = count
    return out


def module_suites() -> dict[str, tuple[str, int, dict[str, int]]]:
    """Gradle path -> (task name, test count, count by source set) for every module that has tests."""
    out: dict[str, tuple[str, int, dict[str, int]]] = {}
    for module in source_dirs(ROOT):
        rel = module.relative_to(ROOT).as_posix()
        # buildSrc runs as a build of its own, see BUILD_LOGIC_RUN.
        if rel == "buildSrc":
            continue
        counts = test_counts(module)
        # A multiplatform module's suite is desktopTest, not test. Asking for
        # the wrong one fails the step rather than skipping quietly.
        task = "desktopTest" if (module / "src" / "desktopTest").is_dir() else "test"
        if counts:
            out[":" + rel.replace("/", ":")] = (task, sum(counts.values()), counts)
    return out


def aggregate_of(gradle_path: str) -> Aggregate | None:
    return next((a for a in AGGREGATES if a.holds(gradle_path)), None)


# buildSrc's suite cannot be named from the root build, so the workflow runs it on
# its own line, and this is the line it has to have.
BUILD_LOGIC_RUN = "./gradlew -p buildSrc test"


def build_logic_unrun() -> bool:
    tests = ROOT / "buildSrc" / "src" / "test"
    if not tests.is_dir():
        return False
    has_tests = any(
        TEST_MARKER.search(f.read_text(encoding="utf-8", errors="replace"))
        for f in tests.rglob("*")
        if f.suffix in SOURCE_EXTS and f.is_file()
    )
    listed = WORKFLOW.is_file() and any(" ".join(words) == BUILD_LOGIC_RUN for words in shell_commands(WORKFLOW.read_text(encoding="utf-8")))
    return has_tests and not listed


def run_values(workflow: str) -> list[str]:
    """
    The value of every `run:` key in [workflow], as YAML gives it to the shell: a
    folded block (`>`, `>-`) has its lines joined by spaces and a blank line kept
    as a line break, a literal block (`|`) keeps its lines, and a plain value is
    itself. A `#` line inside a block is part of the value, not a YAML comment.
    """
    lines = workflow.splitlines()
    values: list[str] = []
    i = 0
    while i < len(lines):
        match = re.match(r"^(\s*)(?:-\s+)?run:\s*(.*)$", lines[i])
        if match is None:
            i += 1
            continue
        indent = len(match.group(1))
        rest = match.group(2).strip()
        i += 1
        if not rest or rest[0] not in ">|":
            values.append(rest)
            continue
        block: list[str] = []
        while i < len(lines) and (not lines[i].strip() or len(lines[i]) - len(lines[i].lstrip()) > indent):
            block.append(lines[i].strip())
            i += 1
        while block and not block[-1]:
            block.pop()
        if rest[0] == "|":
            values.append("\n".join(block))
        else:
            folded = ""
            for line in block:
                if not line:
                    folded += "\n"
                else:
                    folded += ("" if not folded or folded.endswith("\n") else " ") + line
            values.append(folded)
    return values


def shell_commands(workflow: str) -> list[list[str]]:
    """The words of every command line the shell runs for [workflow], each cut at the word a `#` starts, as bash cuts a comment."""
    commands: list[list[str]] = []
    for value in run_values(workflow):
        for line in value.split("\n"):
            words: list[str] = []
            for word in line.split():
                if word.startswith("#"):
                    break
                words.append(word)
            if words:
                commands.append(words)
    return commands


def workflow_tasks() -> set[str]:
    """Every test task a `run:` command of the workflow names, as the shell sees the command."""
    if not WORKFLOW.is_file():
        return set()
    words = [word for command in shell_commands(WORKFLOW.read_text(encoding="utf-8")) for word in command]
    listed = {word for word in words if TASK_WORD.fullmatch(word)}
    listed |= {a.ref for a in AGGREGATES if a.ref in words}
    return listed


def unrun_reason(path: str, task: str, counts: dict[str, int], listed: set[str]) -> str | None:
    """Why the suite of the module at [path] does not run in the workflow, or None where it does."""
    if f"{path}:{task}" in listed:
        return None
    aggregate = aggregate_of(path)
    if aggregate is None:
        return f"is not in {WORKFLOW.name}"
    if aggregate.ref not in listed:
        return f"is not in {WORKFLOW.name}, by itself or through {aggregate.ref}"
    if not aggregate.registered():
        return f"is under {aggregate.ref}, which {aggregate.build_file.relative_to(ROOT)} does not register over its subprojects"
    outside = sorted(name for name in counts if name not in aggregate.covered)
    if outside:
        return f"has tests in {', '.join(outside)}, which {aggregate.ref} does not run (it runs {', '.join(sorted(aggregate.covered))})"
    return None


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

    missing: dict[str, tuple[str, int, str]] = {}
    for path, (task, count, counts) in suites.items():
        reason = unrun_reason(path, task, counts, listed)
        if reason is not None:
            missing[path] = (task, count, reason)
    known = {f"{p}:{v[0]}" for p, v in suites.items()}
    # An aggregate is known while a module under it has tests and it runs them.
    known |= {
        a.ref for a in AGGREGATES
        if a.registered() and any(a.holds(p) for p in suites)
    }
    stale = sorted(t for t in listed if t not in known)

    build_logic = build_logic_unrun()

    if not missing and not stale and not build_logic:
        total = sum(v[1] for v in suites.values())
        print(f"check-ci-suites: {len(suites)} module suites, {total} tests, all in the matrix.")
        return 0

    for path, (task, count, reason) in sorted(missing.items()):
        print(f"[unrun] {path}:{task} has {count} test(s) and {reason}")
    for task in stale:
        if any(a.ref == task for a in AGGREGATES):
            print(f"[stale] {task} is listed in {WORKFLOW.name} but no module under it has tests, or its build file does not register it")
        else:
            print(f"[stale] {task} is listed in {WORKFLOW.name} but that module has no tests")
    if build_logic:
        print(f"[unrun] buildSrc has tests and {WORKFLOW.name} has no '{BUILD_LOGIC_RUN}' step")
    return 1 if args.strict else 0


if __name__ == "__main__":
    sys.exit(main())
