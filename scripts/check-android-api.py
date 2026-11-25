#!/usr/bin/env python3
# Finds calls the oldest Android the launcher runs on does not have.
#
# Shared code compiles against the JDK and against the newest Android, so a method
# that came later than the minimum compiles and dies on the phone, and so does a class
# Android never had. Android Lint says so, in CI and minutes later. This reads the
# classes a local build left behind and asks the SDK's own table (api-versions.xml)
# since when Android has what they call, which takes seconds.
#
# Only classes compiled from source sets Android runs are read. It sees what was
# compiled: a source newer than its class is named as not looked at. A call behind a
# version check is listed in GUARDED, since the bytecode alone does not tell.

from __future__ import annotations

import argparse
import os
import re
import struct
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

from _modules import source_dirs

ROOT = Path(__file__).resolve().parent.parent

# The minSdk of the modules.
MINIMUM = 31

# Source sets whose code runs on Android.
SHARED = ("commonMain", "jvmSharedMain", "androidMain")

# Owners Android is asked about. Anything else is a library and not the platform.
PLATFORM = ("java/", "javax/", "sun/", "jdk/", "com/sun/", "android/")

# What D8 rewrites on the way to dex, so the phone never sees it.
REWRITTEN = ("java/lang/invoke/", "java/lang/runtime/")

# Calls a version check stands in front of, as "source: call", each with why.
GUARDED: dict[str, str] = {}


def sdk_dirs(root: Path) -> list[Path]:
    found = [Path(os.environ[name]) for name in ("ANDROID_HOME", "ANDROID_SDK_ROOT") if os.environ.get(name)]
    local = root / "local.properties"
    if local.is_file():
        m = re.search(r"^sdk\.dir=(.+)$", local.read_text(encoding="utf-8"), re.MULTILINE)
        if m:
            found.append(Path(m.group(1).strip()))
    return found


def table_of(sdk: Path) -> Path | None:
    """The newest platform's table: every one holds the levels of all before it."""
    def version(p: Path) -> tuple[int, ...]:
        return tuple(int(n) for n in re.findall(r"\d+", p.parent.parent.name))
    tables = sorted(sdk.glob("platforms/android-*/data/api-versions.xml"), key=version)
    return tables[-1] if tables else None


class Levels:
    """Since when Android has a class, a method and a field."""

    def __init__(self, xml: str):
        self.classes: dict[str, tuple[int, list[str], dict[str, int]]] = {}
        for node in ET.fromstring(xml).iter("class"):
            since = self._since(node, 1)
            supers = [s.get("name") for s in node if s.tag in ("extends", "implements")]
            members = {m.get("name"): self._since(m, since) for m in node if m.tag in ("method", "field")}
            self.classes[node.get("name")] = (since, supers, members)

    @staticmethod
    def _since(node, default: int) -> int:
        value = node.get("since")
        return int(value.split(".")[0]) if value else default

    def of_class(self, name: str) -> int | None:
        entry = self.classes.get(name)
        return entry[0] if entry else None

    def of_member(self, owner: str, member: str) -> int | None:
        """The earliest level [member] is had at on [owner] or on anything above it, null where it is on none."""
        best: int | None = None
        seen: set[str] = set()
        queue = [owner, "java/lang/Object"]
        while queue:
            name = queue.pop()
            if name in seen or name not in self.classes:
                continue
            seen.add(name)
            since, supers, members = self.classes[name]
            if member in members:
                level = max(since, members[member]) if name == owner else members[member]
                best = level if best is None else min(best, level)
            queue.extend(supers)
        return best


def read_class(data: bytes) -> tuple[str, str | None, list[tuple[str, str]], list[str]]:
    """A class file's name, its source file, the members it refers to as (owner, member) and the classes."""
    pool: list = [None]
    at = 10
    count = struct.unpack_from(">H", data, 8)[0]
    while len(pool) < count:
        tag = data[at]
        if tag == 1:
            size = struct.unpack_from(">H", data, at + 1)[0]
            pool.append(data[at + 3:at + 3 + size].decode("utf-8", "replace"))
            at += 3 + size
        elif tag in (5, 6):
            pool += [None, None]
            at += 9
        elif tag in (7, 8, 16, 19, 20):
            pool.append((tag, struct.unpack_from(">H", data, at + 1)[0]))
            at += 3
        elif tag == 15:
            pool.append(None)
            at += 4
        elif tag in (3, 4):
            pool.append(None)
            at += 5
        elif tag in (9, 10, 11, 12, 17, 18):
            pool.append((tag, *struct.unpack_from(">HH", data, at + 1)))
            at += 5
        else:
            raise ValueError(f"constant tag {tag}")
    this = pool[pool[struct.unpack_from(">H", data, at + 2)[0]][1]]
    members, classes = [], []
    for entry in pool:
        if not isinstance(entry, tuple):
            continue
        if entry[0] in (9, 10, 11):
            owner = pool[pool[entry[1]][1]]
            _, name, descriptor = pool[entry[2]]
            members.append((owner, pool[name] if entry[0] == 9 else pool[name] + pool[descriptor]))
        elif entry[0] == 7:
            classes.append(pool[entry[1]])
    return this, source_file(data, pool, at), members, classes


def source_file(data: bytes, pool: list, at: int) -> str | None:
    """The SourceFile attribute, past the interfaces, the fields and the methods."""
    at += 6
    at += 2 + 2 * struct.unpack_from(">H", data, at)[0]
    for _ in range(2):
        n = struct.unpack_from(">H", data, at)[0]
        at += 2
        for _ in range(n):
            at = skip_attributes(data, at + 6)
    n = struct.unpack_from(">H", data, at)[0]
    at += 2
    for _ in range(n):
        name, size = struct.unpack_from(">HI", data, at)
        if pool[name] == "SourceFile":
            return pool[struct.unpack_from(">H", data, at + 6)[0]]
        at += 6 + size
    return None


def skip_attributes(data: bytes, at: int) -> int:
    n = struct.unpack_from(">H", data, at)[0]
    at += 2
    for _ in range(n):
        at += 6 + struct.unpack_from(">I", data, at + 2)[0]
    return at


def faults(data: bytes, levels: Levels) -> list[str]:
    """What the class in [data] uses that Android [MINIMUM] does not have."""
    _, _, members, classes = read_class(data)
    found: set[str] = set()
    missing: set[str] = set()
    for name in classes:
        name = name.lstrip("[")
        if name.startswith("L") and name.endswith(";"):
            name = name[1:-1]
        if not name.startswith(PLATFORM) or name.startswith(REWRITTEN):
            continue
        since = levels.of_class(name)
        if since is None or since > MINIMUM:
            missing.add(name)
            found.add(f"{name.replace('/', '.')} ({'never on Android' if since is None else f'API {since}'})")
    for owner, member in members:
        if owner.startswith("[") or not owner.startswith(PLATFORM) or owner.startswith(REWRITTEN) or owner in missing:
            continue
        since = levels.of_member(owner, member)
        if since is None or since > MINIMUM:
            call = f"{owner.replace('/', '.')}.{member.split('(')[0]}"
            found.add(f"{call} ({'never on Android' if since is None else f'API {since}'})")
    return sorted(found)


def classes_of(module: Path) -> dict[str, Path]:
    """The newest class file of each name the module's JVM and Android compilations left."""
    newest: dict[str, Path] = {}
    for target in ("android", "jvm", "desktop"):
        base = module / "build/classes/kotlin" / target / "main"
        for path in base.rglob("*.class") if base.is_dir() else ():
            rel = path.relative_to(base).as_posix()
            if rel not in newest or path.stat().st_mtime > newest[rel].stat().st_mtime:
                newest[rel] = path
    return newest


def main() -> int:
    parser = argparse.ArgumentParser(description="Calls newer than the oldest Android the launcher runs on.")
    parser.add_argument("--strict", action="store_true", help="exit non-zero on any finding")
    args = parser.parse_args()

    table = next((t for t in map(table_of, sdk_dirs(ROOT)) if t), None)
    if table is None:
        print("android-api: no Android SDK with a platform found, nothing checked")
        return 1 if args.strict else 0
    levels = Levels(table.read_text(encoding="utf-8"))

    read, found, stale = 0, set(), set()
    for module in source_dirs(ROOT):
        sources = {p.relative_to(module / "src" / s / "kotlin").as_posix(): p
                   for s in SHARED for p in (module / "src" / s / "kotlin").rglob("*.kt")}
        if not sources:
            continue
        looked: set[str] = set()
        for rel, path in classes_of(module).items():
            data = path.read_bytes()
            _, source, _, _ = read_class(data)
            key = f"{rel.rsplit('/', 1)[0]}/{source}" if "/" in rel else source
            origin = sources.get(key)
            if origin is None:
                continue
            read += 1
            looked.add(key)
            name = origin.relative_to(ROOT).as_posix()
            if origin.stat().st_mtime > path.stat().st_mtime:
                stale.add(name)
            found |= {(name, f) for f in faults(data, levels) if f"{name}: {f.split(' ')[0]}" not in GUARDED}
        stale |= {sources[k].relative_to(ROOT).as_posix() for k in sources.keys() - looked if declares(sources[k])}

    for name, fault in sorted(found):
        print(f"{name}: {fault}")
    if stale:
        print(f"android-api: {len(stale)} source(s) newer than their classes or never compiled, not looked at as they stand")
    if not found:
        print(f"android-api: nothing newer than API {MINIMUM} in {read} classes ({table.parent.parent.name})")
        return 0
    print(f"\n{len(found)} use(s) Android {MINIMUM} does not have")
    return 1 if args.strict else 0


def declares(source: Path) -> bool:
    """Whether [source] holds anything that compiles to a class of its own."""
    text = source.read_text(encoding="utf-8", errors="replace")
    return re.search(r"^(?!\s*(?:typealias|import|package|//|\*|/\*))\s*(?:[\w@()]+\s+)*(?:class|object|fun|val|var)\s", text, re.MULTILINE) is not None


if __name__ == "__main__":
    sys.exit(main())
