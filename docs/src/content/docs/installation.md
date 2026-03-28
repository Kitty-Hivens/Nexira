---
title: Installation
description: How to install Aura Launcher on Windows, Linux and macOS.
---

Download the latest release from [GitHub Releases](https://github.com/Kitty-Hivens/Aura-Launcher/releases/latest).

## Windows

**Installer (recommended)**

1. Download `AuraLauncher-*-Setup.exe`
2. Run it — no admin rights required
3. The launcher installs to `%AppData%\AuraLauncher`

**Portable**

1. Download `AuraLauncher-*-Windows-Portable.zip`
2. Extract anywhere
3. Run `AuraLauncher.exe`

## Linux

1. Download `AuraLauncher-*-x86_64.AppImage`
2. Make it executable and run:

```bash
chmod +x AuraLauncher-*.AppImage
./AuraLauncher-*.AppImage
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

1. Download `AuraLauncher-*.dmg`
2. Open the DMG and drag the app to Applications
3. On first launch, right-click → **Open** if macOS blocks it

:::note
**Apple Silicon** — native.  
**Intel** — supported via Rosetta 2, installed automatically if not present.
:::

## First launch

1. Log in with your SMARTYcraft credentials
2. Select a server
3. Hit **Play** — the launcher syncs game files automatically on first run

Data is stored in:
- Linux/macOS: `~/.aura/`
- Windows: `%AppData%\.aura`
