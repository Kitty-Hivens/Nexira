---
title: Installation
description: How to install Nexira on Windows, Linux and macOS.
---

Download the latest release from [GitHub Releases](https://github.com/Kitty-Hivens/Nexira/releases/latest).

## Windows

**Installer (recommended)**

1. Download `Nexira-*-Setup.exe`
2. Run it -- no admin rights required
3. The launcher installs to `%AppData%\Nexira`

**Portable**

1. Download `Nexira-*-Windows-Portable.zip`
2. Extract anywhere
3. Run `Nexira.exe`

## Linux

1. Download `Nexira-*-x86_64.AppImage`
2. Make it executable and run:

```bash
chmod +x Nexira-*.AppImage
./Nexira-*.AppImage
```

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
**Intel** -- community-tier build (`*-macos-x86_64-community.dmg`); CI-validated but not part of the primary release set.
:::

## First launch

1. Log in with your SMARTYcraft credentials
2. Select a server
3. Hit **Play** -- the launcher syncs game files automatically on first run

### Upgrading from Aura

If you previously had Aura installed, Nexira detects the old data directory on first start and shows a mandatory migration screen. Click **Migrate now**, wait for the copy to finish, then click **Quit Nexira** and re-launch. The original Aura folder is left untouched as a backup; delete it manually once you've confirmed everything works.

## Where your data lives

| Platform | Path |
|---|---|
| Windows | `%LOCALAPPDATA%\Nexira` |
| macOS | `~/Library/Application Support/Nexira` |
| Linux | `$XDG_DATA_HOME/nexira` (default `~/.local/share/nexira`) |

The install directory is separate -- `%AppData%\Nexira` (Windows installer) or the AppImage location on Linux. User data (clients, settings, skin cache, crash reports, logs) lives at the paths above.

To override the default location for any platform, set the `NEXIRA_DATA_DIR` environment variable to an absolute path before launching. Alternatively, **Settings → Data directory → Move** picks a new location through a native folder picker and applies the move on next start.
