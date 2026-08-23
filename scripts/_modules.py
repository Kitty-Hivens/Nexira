"""Where the source modules are, for the scanners that walk them.

Derived from `settings.gradle.kts` rather than from a directory listing. The
build file is the authority on what a module is, and each scanner's own listing
walked one level down from the repository root -- which quietly skipped the two
modules that live under `experimental/` and `examples/`, the same failure the
hand-maintained lists had before they were derived at all.

`buildSrc` is not an included project and is appended on its own; it carries
Kotlin the same rules apply to.
"""

from __future__ import annotations

import re
from pathlib import Path

INCLUDE = re.compile(r'^\s*include\("([^"]+)"\)', re.MULTILINE)


def module_dirs(root: Path) -> list[Path]:
    """Every included project directory that exists on disk, plus buildSrc."""
    settings = root / "settings.gradle.kts"
    found: set[Path] = set()
    if settings.is_file():
        for match in INCLUDE.finditer(settings.read_text(encoding="utf-8")):
            path = root / match.group(1).lstrip(":").replace(":", "/")
            if path.is_dir():
                found.add(path)
    build_src = root / "buildSrc"
    if build_src.is_dir():
        found.add(build_src)
    return sorted(found)


def source_dirs(root: Path) -> list[Path]:
    """Included projects that actually carry sources."""
    return [p for p in module_dirs(root) if (p / "src").is_dir()]
