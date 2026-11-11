---
title: Installation
description: How to install Nexira on Windows, Linux and macOS.
---

Download the latest release from [GitHub Releases](https://github.com/Kitty-Hivens/Nexira/releases/latest).

## Windows

Windows releases are not code signed yet. On a system with Smart App Control turned on, Windows blocks the launcher, and the only way past it is to turn Smart App Control off. See the [code signing policy](../code-signing/).

**Installer (recommended)**

1. Download `Nexira-*-Setup.exe`
2. Run it -- no admin rights required
3. The launcher installs to `%LocalAppData%\Nexira\Programs`

**Portable**

1. Download `Nexira-*-Windows-Portable.zip`
2. Extract anywhere
3. Run `Nexira.exe`

## Linux

1. Download `Nexira-x86_64.AppImage` (a nightly build is `Nexira-nightly-x86_64.AppImage`)
2. Make it executable and run:

```bash
chmod +x Nexira-x86_64.AppImage
./Nexira-x86_64.AppImage
```

The launcher updates this file in place, so it keeps its name and location from one version to the next. A nightly kept in the same folder is a separate file and is never overwritten by the release, nor the other way round.

:::tip
Most desktop environments let you right-click → Properties → Allow executing as program.
:::

:::note[FUSE required]
AppImage requires FUSE. On Ubuntu 22.04+ it may not be installed by default:

```bash
sudo apt install libfuse2
```
:::

## macOS

1. Download `Nexira-*.dmg`
2. Open the DMG and drag the app to Applications
3. On first launch, right-click → **Open** if macOS blocks it

:::note
**Apple Silicon** -- native build.  
**Intel** -- community-tier build (`Nexira-*-x86_64-community.dmg`); CI-validated but not part of the primary release set.
:::

## Android (beta)

1. Download `Nexira-*-Android-arm64.apk` on the phone
2. Open it and allow installing apps from that source when Android asks
3. Install and open Nexira

It needs Android 12 or newer on a 64-bit ARM phone. On the phone a game runs on LWJGL 3: Minecraft from 1.14 on, and 1.12.2 and 1.7.10 packs on Cleanroom or lwjgl3ify. Forge from 1.13 on and NeoForge are not offered there yet.

## First launch

1. Log in with your SMARTYcraft credentials
2. Select a server
3. Hit **Play** -- the launcher syncs game files automatically on first run

### Upgrading from Aura

If you previously had Aura installed, Nexira detects the old data directory on first start and shows a mandatory migration screen. Click **Migrate now**, wait for the copy to finish, then click **Quit Nexira** and re-launch. The original Aura folder is left untouched as a backup; delete it manually once you've confirmed everything works.

## Where your data lives

| Platform | Path                                                      |
|----------|-----------------------------------------------------------|
| Windows  | `%LOCALAPPDATA%\Nexira`                                   |
| macOS    | `~/Library/Application Support/Nexira`                    |
| Linux    | `$XDG_DATA_HOME/nexira` (default `~/.local/share/nexira`) |

The install directory is separate -- `%LocalAppData%\Nexira\Programs` (Windows installer) or the AppImage location on Linux. User data (clients, settings, skin cache, crash reports, logs) lives at the paths above.

To override the default location for any platform, set the `NEXIRA_DATA_DIR` environment variable to an absolute path before launching. Alternatively, **Settings → Advanced → Data directory → Move...** picks a new location through a native folder picker and applies the move on next start.
