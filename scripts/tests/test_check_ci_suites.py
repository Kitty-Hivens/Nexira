"""The matrix gate reads the workflow the way bash runs it and the aggregate the way Gradle builds it.

Run with `python3 -m unittest discover -s scripts/tests`, which the suite-lint workflow does.
"""

from __future__ import annotations

import importlib.util
import sys
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(SCRIPTS))
_spec = importlib.util.spec_from_file_location("check_ci_suites", SCRIPTS / "check-ci-suites.py")
check = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(check)

STEP = """
      - name: Run unit tests
        run: >-
          ./gradlew
          :a:test :b:test
{middle}
          :c:test
          --continue
"""


def tasks(workflow: str) -> set[str]:
    words = [word for command in check.shell_commands(workflow) for word in command]
    return {word for word in words if check.TASK_WORD.fullmatch(word)}


class RunLineTest(unittest.TestCase):

    def test_a_folded_block_is_one_command(self):
        self.assertEqual({":a:test", ":b:test", ":c:test"}, tasks(STEP.format(middle="")))

    def test_a_hash_line_inside_the_block_drops_every_later_task(self):
        found = tasks(STEP.format(middle="          # the look modules below"))
        self.assertEqual({":a:test", ":b:test"}, found, "bash reads the folded block as one line and the # starts a comment")

    def test_a_trailing_note_drops_the_rest_of_the_command(self):
        found = tasks(STEP.format(middle="          :d:test # added later"))
        self.assertEqual({":a:test", ":b:test", ":d:test"}, found)

    def test_a_task_named_only_in_a_yaml_comment_runs_nothing(self):
        workflow = "      # :z:test was absent here\n" + STEP.format(middle="")
        self.assertNotIn(":z:test", tasks(workflow))

    def test_a_hash_inside_a_word_is_not_a_comment(self):
        found = tasks(STEP.format(middle="          -Pnote=a#b :d:test"))
        self.assertIn(":d:test", found)

    def test_the_build_logic_line_is_found_as_a_command(self):
        workflow = "      - name: Logic\n        run: ./gradlew -p buildSrc test\n"
        self.assertIn(check.BUILD_LOGIC_RUN.split(), check.shell_commands(workflow))
        commented = "      - name: Logic\n        run: echo skipped # ./gradlew -p buildSrc test\n"
        self.assertNotIn(check.BUILD_LOGIC_RUN.split(), check.shell_commands(commented))


class AggregateTest(unittest.TestCase):

    DEPENDS = check.Aggregate.DEPENDS

    def registers(self, script: str) -> bool:
        return check.registers_over_subprojects(script, "allTests", self.DEPENDS)

    def test_the_look_build_file_registers_the_aggregate_over_every_module(self):
        self.assertTrue(check.AGGREGATES[0].registered())

    def test_an_empty_registration_with_subprojects_in_a_comment_runs_nothing(self):
        script = '// every subprojects test runs here\ntasks.register("allTests") {}\n'
        self.assertFalse(self.registers(script))

    def test_the_dependency_in_a_comment_does_not_count(self):
        script = 'tasks.register("allTests") {\n    // dependsOn(subprojects.map { "${it.path}:test" })\n}\n'
        self.assertFalse(self.registers(script))
        block = 'tasks.register("allTests") {\n    /* dependsOn(subprojects.map { "${it.path}:test" }) */\n}\n'
        self.assertFalse(self.registers(block))

    def test_the_dependency_outside_the_registration_does_not_count(self):
        script = 'tasks.register("allTests") {}\nval x = dependsOn(subprojects.map { "${it.path}:test" })\n'
        self.assertFalse(self.registers(script))

    def test_the_dependency_inside_the_registration_counts(self):
        script = 'tasks.register("allTests") {\n    group = "verification"\n    dependsOn(subprojects.map { "${it.path}:test" })\n}\n'
        self.assertTrue(self.registers(script))


if __name__ == "__main__":
    unittest.main()
