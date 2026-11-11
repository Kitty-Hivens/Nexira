#!/usr/bin/env python3
"""Pins the Java builds for Android that the nexira-android-jdk repository has released.

    scripts/pin-android-jdk.py jdk-8u492 jdk-17.0.21 jdk-21.0.13 jdk-25.0.5

Reads the SHA-256 file published beside each archive and rewrites AndroidJdkPins.kt with one entry per tag.
The launcher pins the digest in its own source rather than trusting the host that serves the archive, so
this runs when a release is cut and its output is committed with the launcher.
"""
import re
import sys
import urllib.request
from pathlib import Path

RELEASES = "https://github.com/Kitty-Hivens/nexira-android-jdk/releases/download"
OUT = Path(__file__).resolve().parent.parent / "client-launcher/src/jvmSharedMain/kotlin/hivens/launcher/runtime/AndroidJdkPins.kt"


def major_of(version: str) -> int:
    # 8u492 is Java 8, 17.0.21 is Java 17
    return 8 if re.match(r"8u\d+$", version) else int(version.split(".")[0])


def reported_version(version: str) -> str:
    # What `release` says: 1.8.0_492 for 8u492, the tag's own version for the rest
    return f"1.8.0_{version.split('u')[1]}" if major_of(version) == 8 else version


def digest(version: str) -> str:
    url = f"{RELEASES}/jdk-{version}/jdk-{version}-android-aarch64.zip.sha256"
    with urllib.request.urlopen(url) as response:
        line = response.read().decode().strip()
    found = line.split()[0].lower()
    if not re.fullmatch(r"[0-9a-f]{64}", found):
        raise SystemExit(f"{url} does not hold a SHA-256: {line!r}")
    return found


def main(tags: list[str]) -> None:
    if not tags:
        raise SystemExit(__doc__)
    entries = []
    for tag in tags:
        version = tag.removeprefix("jdk-")
        entries.append((major_of(version), reported_version(version), version, digest(version)))
    entries.sort()
    rows = "\n".join(
        f'    AndroidJdkRelease({major}, "{reported}", AndroidJdkCatalog.assetUrl("{version}"),\n'
        f'        "{sha}"),'
        for major, reported, version, sha in entries
    )
    OUT.write_text(
        "package hivens.launcher.runtime\n\n"
        "/**\n"
        " * The Java builds for Android this launcher was released with, one per major.\n"
        " * Written by scripts/pin-android-jdk.py from the releases of the build repository.\n"
        " */\n"
        "internal val ANDROID_JDK_PINS: List<AndroidJdkRelease> = listOf(\n" + rows + "\n)\n"
    )
    print(f"pinned {len(entries)} builds in {OUT}")


if __name__ == "__main__":
    main(sys.argv[1:])
