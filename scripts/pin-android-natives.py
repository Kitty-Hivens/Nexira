#!/usr/bin/env python3
"""Pins the natives for Android that the nexira-android-lwjgl repository has released.

    scripts/pin-android-natives.py 3.3.3

Reads the SHA-256 file published beside each archive of the release lwjgl-<version> and rewrites AndroidNativesPins.kt
with one entry per LWJGL version. The launcher pins the digests in its own source rather than trusting the host that
serves the archives, so this runs when a release is cut and its output is committed with the launcher.
"""
import re
import sys
import urllib.request
from pathlib import Path

RELEASES = "https://github.com/Kitty-Hivens/nexira-android-lwjgl/releases/download"
OUT = Path(__file__).resolve().parent.parent / "client-launcher/src/jvmSharedMain/kotlin/hivens/launcher/runtime/AndroidNativesPins.kt"


def archives(version: str) -> list[str]:
    # What scripts/package.sh and scripts/package-host.sh name their archives
    return [f"lwjgl-{version}-natives-android-arm64.jar", "nexira-host-natives-android-arm64.jar"]


def digest(version: str, name: str) -> str:
    url = f"{RELEASES}/lwjgl-{version}/{name}.sha256"
    with urllib.request.urlopen(url) as response:
        line = response.read().decode().strip()
    found = line.split()[0].lower()
    if not re.fullmatch(r"[0-9a-f]{64}", found):
        raise SystemExit(f"{url} does not hold a SHA-256: {line!r}")
    return found


def main(versions: list[str]) -> None:
    if not versions:
        raise SystemExit(__doc__)
    releases = []
    for version in sorted(versions):
        assets = ",\n".join(
            f'            AndroidNativesAsset("{name}", AndroidNativesCatalog.assetUrl("{version}", "{name}"),\n'
            f'                "{digest(version, name)}")'
            for name in archives(version)
        )
        releases.append(f'    AndroidNativesRelease("{version}", listOf(\n{assets},\n    )),')
    OUT.write_text(
        "package hivens.launcher.runtime\n\n"
        "/**\n"
        " * The natives for Android this launcher was released with, one release per LWJGL version.\n"
        " * Written by scripts/pin-android-natives.py from the releases of the build repository.\n"
        " */\n"
        "internal val ANDROID_NATIVES_PINS: List<AndroidNativesRelease> = listOf(\n" + "\n".join(releases) + "\n)\n"
    )
    print(f"pinned {len(releases)} releases in {OUT}")


if __name__ == "__main__":
    main(sys.argv[1:])
