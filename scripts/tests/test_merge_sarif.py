"""Lint's reports merge into one run that code scanning accepts and that points at the right lines.

Run with `python3 -m unittest discover -s scripts/tests`, which the suite-lint workflow does.
"""

from __future__ import annotations

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent.parent
_spec = importlib.util.spec_from_file_location("merge_sarif", SCRIPTS / "merge-sarif.py")
merge_sarif = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(merge_sarif)

ROOT = Path("/work/repo")


def rule(rule_id: str) -> dict:
    return {"id": rule_id, "shortDescription": {"text": rule_id}}


def finding(rule_id: str, uri: str, line: int, index: int | None = None, base: str | None = "%SRCROOT%") -> dict:
    location = {"uri": uri}
    if base is not None:
        location["uriBaseId"] = base
    result = {
        "ruleId": rule_id,
        "message": {"text": f"{rule_id} here"},
        "locations": [{"physicalLocation": {"artifactLocation": location, "region": {"startLine": line}}}],
    }
    if index is not None:
        result["ruleIndex"] = index
    return result


def report(srcroot: str, rules: list[dict], results: list[dict]) -> dict:
    return {
        "$schema": "https://json.schemastore.org/sarif-2.1.0.json",
        "version": "2.1.0",
        "runs": [
            {
                "tool": {"driver": {"name": "Android Lint", "rules": rules}},
                "originalUriBaseIds": {"%SRCROOT%": {"uri": srcroot}},
                "results": results,
            }
        ],
    }


def location(result: dict) -> dict:
    return result["locations"][0]["physicalLocation"]["artifactLocation"]


class MergeTest(unittest.TestCase):

    def test_reports_become_one_run(self):
        merged = merge_sarif.merge(
            [report("file:///work/repo/", [], []), report("file:///work/repo/", [], [])],
            ROOT,
        )
        self.assertEqual(1, len(merged["runs"]))

    def test_a_file_named_against_a_module_root_is_named_against_the_repository(self):
        merged = merge_sarif.merge(
            [report("file:///work/repo/client-ui/", [rule("NewApi")], [finding("NewApi", "src/A.kt", 3)])],
            ROOT,
        )
        found = location(merged["runs"][0]["results"][0])
        self.assertEqual({"uri": "client-ui/src/A.kt", "uriBaseId": "%SRCROOT%"}, found)
        self.assertEqual("file:///work/repo/", merged["runs"][0]["originalUriBaseIds"]["%SRCROOT%"]["uri"])

    def test_a_base_without_a_trailing_slash_still_keeps_its_last_folder(self):
        merged = merge_sarif.merge(
            [report("file:///work/repo/packs", [rule("NewApi")], [finding("NewApi", "src/B.kt", 1)])],
            ROOT,
        )
        self.assertEqual("packs/src/B.kt", location(merged["runs"][0]["results"][0])["uri"])

    def test_a_file_outside_the_repository_keeps_its_absolute_name(self):
        merged = merge_sarif.merge(
            [report("file:///work/repo/", [rule("X")], [finding("X", "file:///home/runner/.gradle/x.jar", 1, base=None)])],
            ROOT,
        )
        self.assertEqual({"uri": "file:///home/runner/.gradle/x.jar"}, location(merged["runs"][0]["results"][0]))

    def test_rules_join_by_id_and_each_index_points_at_its_rule(self):
        merged = merge_sarif.merge(
            [
                report("file:///work/repo/", [rule("A"), rule("B")], [finding("B", "x.kt", 1, index=1)]),
                report("file:///work/repo/", [rule("C"), rule("A")], [finding("A", "y.kt", 2, index=1), finding("C", "z.kt", 3, index=0)]),
            ],
            ROOT,
        )
        run = merged["runs"][0]
        ids = [r["id"] for r in run["tool"]["driver"]["rules"]]
        self.assertEqual(["A", "B", "C"], ids)
        for result in run["results"]:
            self.assertEqual(result["ruleId"], ids[result["ruleIndex"]])

    def test_a_finding_both_lint_tasks_report_is_kept_once(self):
        shared = finding("NewApi", "src/commonMain/A.kt", 7)
        merged = merge_sarif.merge(
            [
                report("file:///work/repo/i18n/", [rule("NewApi")], [json.loads(json.dumps(shared))]),
                report("file:///work/repo/i18n/", [rule("NewApi")], [json.loads(json.dumps(shared))]),
            ],
            ROOT,
        )
        self.assertEqual(1, len(merged["runs"][0]["results"]))

    def test_the_same_line_in_two_modules_is_two_findings(self):
        merged = merge_sarif.merge(
            [
                report("file:///work/repo/a/", [rule("X")], [finding("X", "src/A.kt", 1)]),
                report("file:///work/repo/b/", [rule("X")], [finding("X", "src/A.kt", 1)]),
            ],
            ROOT,
        )
        self.assertEqual(2, len(merged["runs"][0]["results"]))

    def test_no_report_is_an_error_not_an_empty_upload(self):
        with tempfile.TemporaryDirectory() as folder:
            self.assertEqual(1, merge_sarif.main(["--root", folder, "--out", str(Path(folder) / "out.sarif")]))


if __name__ == "__main__":
    unittest.main()
