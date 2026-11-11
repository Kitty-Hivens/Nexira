---
title: Building from Source
description: How to build and run Nexira locally.
---

## Requirements

- An installed JDK 27, any vendor. The Gradle daemon has to run on it, and Gradle finds it on its own.
- The included `./gradlew` wrapper, which brings its own Gradle.
- For the Android app only: the Android SDK, found through `ANDROID_HOME` or `local.properties`. Without it the Android module is left out of the build and everything else still builds.

## Clone and run

```bash
git clone https://github.com/Kitty-Hivens/Nexira.git
cd Nexira

# Run in development mode
./gradlew :client-ui:run

# Run the tests of one module
./gradlew :client-launcher:test
```

Every module with tests is listed in `.github/workflows/tests.yml`, which is the command line the pull request checks run.

## Build release artifacts

```bash
# Windows: the jpackage app image, fed into Inno Setup (setup.iss) for the installer
./gradlew :client-ui:customJpackageImage

# macOS: DMG
./gradlew :client-ui:customDmg

# Linux: the uber jar, which scripts/build-appimage.sh wraps into the AppImage
./gradlew :client-ui:packageReleaseUberJarForCurrentOS

# Android: APK
./gradlew :android-app:assembleRelease
```

Packaging runs on the system it packages for: a Windows image is built on Windows, a DMG on macOS.

## CI / Release pipeline

Releases are built by `.github/workflows/build_release.yml` when a tag (`v*.*.*`) is pushed.

The pipeline:
1. Runs the tests. The build fails if any test fails.
2. Builds the Windows installer and portable ZIP, the Linux AppImage, the macOS DMG and the Android APK in parallel.
3. Generates SHA256 checksums.
4. Publishes a GitHub Release with the notes extracted from `CHANGELOG.md`.

Nightly builds come from `.github/workflows/nightly.yml`, every night from the development branch.

## Platform notes

:::note[AppImage on Linux]
Assembled by `scripts/build-appimage.sh` with `appimagetool` and a `jlink`-built runtime, not by Compose's built-in packaging. See the `build-linux` job in `build_release.yml`.
:::

:::note[The runtime on Windows]
`jlink` takes the runtime's native files unsigned from the JDK's jmods. The build swaps each one for the signed copy in the JDK's own `bin` when the two are the same file apart from the signature, so the runtime ships with the JDK vendor's signatures.
:::
