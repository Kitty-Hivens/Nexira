#!/usr/bin/env python3
"""Merge Android Lint's SARIF reports into one file with one run.

Lint writes a report per module and per target, each a run of the same tool.
Code scanning takes one run per category and refuses a file that brings several
under one, so the reports go up merged.

Each run names its files against its own %SRCROOT%. The merged run names every
file against the repository root, so a finding points at the same line whichever
report it came from. Rules are joined by id, and a finding two reports share
(common code is read by both the Android and the JVM lint task) is kept once.

    python3 scripts/merge-sarif.py --root . --out lint.sarif a.sarif b.sarif
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path
from urllib.parse import unquote, urljoin, urlparse

SRCROOT = "%SRCROOT%"


def root_uri(root: Path) -> str:
    return root.resolve().as_uri() + "/"


def base_uris(run: dict) -> dict[str, str]:
    bases = {}
    for name, location in (run.get("originalUriBaseIds") or {}).items():
        uri = location.get("uri")
        if uri:
            bases[name] = uri if uri.endswith("/") else uri + "/"
    return bases


def rebase(location: dict, bases: dict[str, str], root: Path) -> None:
    """Name the file against the repository root when it lies inside it."""
    uri = location.get("uri")
    if uri is None:
        return
    base = location.get("uriBaseId")
    absolute = urljoin(bases[base], uri) if base in bases else uri
    parsed = urlparse(absolute)
    if parsed.scheme != "file":
        return
    path = Path(unquote(parsed.path))
    try:
        relative = path.relative_to(root.resolve())
    except ValueError:
        location.pop("uriBaseId", None)
        location["uri"] = absolute
        return
    location["uri"] = relative.as_posix()
    location["uriBaseId"] = SRCROOT


def walk_locations(node, bases: dict[str, str], root: Path) -> None:
    if isinstance(node, dict):
        for key, value in node.items():
            if key == "artifactLocation" and isinstance(value, dict):
                rebase(value, bases, root)
            else:
                walk_locations(value, bases, root)
    elif isinstance(node, list):
        for item in node:
            walk_locations(item, bases, root)


def identity(result: dict) -> str:
    return json.dumps(
        [result.get("ruleId"), result.get("message"), result.get("locations")],
        sort_keys=True,
    )


def merge(reports: list[dict], root: Path) -> dict:
    driver = None
    rules: dict[str, dict] = {}
    results: list[dict] = []
    seen: set[str] = set()
    for report in reports:
        for run in report.get("runs", []):
            run_driver = run.get("tool", {}).get("driver", {})
            if driver is None:
                driver = {key: value for key, value in run_driver.items() if key != "rules"}
            for rule in run_driver.get("rules", []):
                rules.setdefault(rule["id"], rule)
            bases = base_uris(run)
            for result in run.get("results", []):
                walk_locations(result, bases, root)
                key = identity(result)
                if key in seen:
                    continue
                seen.add(key)
                results.append(result)
    if driver is None:
        raise ValueError("no SARIF run to merge")
    order = list(rules)
    for result in results:
        rule_id = result.get("ruleId")
        if rule_id in rules:
            result["ruleIndex"] = order.index(rule_id)
        else:
            result.pop("ruleIndex", None)
    driver["rules"] = [rules[rule_id] for rule_id in order]
    return {
        "$schema": reports[0].get("$schema", "https://json.schemastore.org/sarif-2.1.0.json"),
        "version": reports[0].get("version", "2.1.0"),
        "runs": [
            {
                "tool": {"driver": driver},
                "originalUriBaseIds": {SRCROOT: {"uri": root_uri(root)}},
                "results": results,
            }
        ],
    }


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--root", type=Path, required=True, help="the repository root")
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("reports", type=Path, nargs="*")
    args = parser.parse_args(argv)
    if not args.reports:
        print("merge-sarif: no reports given, lint wrote none", file=sys.stderr)
        return 1
    reports = [json.loads(path.read_text(encoding="utf-8")) for path in args.reports]
    merged = merge(reports, args.root)
    args.out.write_text(json.dumps(merged, indent=2), encoding="utf-8")
    run = merged["runs"][0]
    print(f"merge-sarif: {len(args.reports)} reports, {len(run['tool']['driver']['rules'])} rules, {len(run['results'])} findings")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
