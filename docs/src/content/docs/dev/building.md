---
title: Building from Source
description: How to build and run Nexira locally.
---

## Requirements

- JDK 25+
- Gradle 9+ (or use the included `./gradlew` wrapper)

## Clone and run

```bash
git clone https://github.com/Kitty-Hivens/Nexira.git
cd Nexira

# Run in development mode
./gradlew :client-ui:run

# Run tests
./gradlew :client-core:test :client-launcher:test
```

## Build release artifacts

```bash
# Windows — distributable folder (fed into Inno Setup)
./gradlew :client-ui:createReleaseDistributable

# macOS — DMG
./gradlew :client-ui:packageReleaseDmg

# Linux — uber JAR (AppImage is assembled manually in CI)
./gradlew :client-ui:packageReleaseUberJarForCurrentOS
```

## CI / Release pipeline

Releases are built automatically by `.github/workflows/build_release.yml` on tag push (`v*.*.*`).

The pipeline:
1. Runs tests — build fails if any test fails
2. Builds Windows EXE (Inno Setup), Portable ZIP, Linux AppImage, macOS DMG in parallel
3. Generates SHA256 checksums
4. Publishes a GitHub Release with changelog extracted from `CHANGELOG.md`

## Platform notes

:::caution[JNA on Windows]
JNA is pinned to `5.18.1` in `client-ui` and forced to `6.1.6` globally via Gradle `resolutionStrategy`. Do not change — dorkbox/SystemTray 4.4 has a hardcoded version check that will crash the launcher if the wrong version is loaded.
:::

:::note[AppImage on Linux]
Assembled manually in CI using `appimagetool` with a `jlink`-built minimal JRE. Not via Compose's built-in packaging. See the `build-linux` job in `build_release.yml`.
:::
