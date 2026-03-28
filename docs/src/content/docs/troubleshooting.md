---
title: Troubleshooting
description: Common issues and how to fix them.
---

## Where are the logs?

| | Path |
|---|---|
| Crash reports | `~/.aura/crash-reports/` (Linux/macOS) or `%AppData%\.aura\crash-reports` (Windows) |
| Console output | Wrench icon in sidebar → **Copy All** |

:::tip[Reporting a bug?]
Always attach the crash report or console output. [Open a bug report →](https://github.com/Kitty-Hivens/Aura-Launcher/issues/new?template=bug_report.md)
:::

---

## Launcher won't start

**Windows — crashes silently before the window appears**

Make sure you're running the official installer or portable ZIP from the [releases page](https://github.com/Kitty-Hivens/Aura-Launcher/releases/latest), not an older build. JNA conflicts from old builds can cause silent crashes.

**Linux — AppImage won't run**

Install FUSE:

```bash
# Ubuntu/Debian
sudo apt install libfuse2

# Arch
sudo pacman -S fuse2
```

**Linux — blank window or crash on Wayland**

Try forcing XWayland:

```bash
_JAVA_AWT_WM_NONREPARENTING=1 ./AuraLauncher-*.AppImage
```

---

## Login issues

**"SSL certificate error" warning**

The game server's SSL certificate may have expired. The launcher will show a warning — you can choose to connect anyway. Your credentials are not sent to any third party.

**"Bad login" or "User not found"**

Check your credentials on [smartycraft.ru](https://www.smartycraft.ru). The launcher uses the same login as the website.

---

## Game won't launch

**Files keep re-downloading every time**

Normal on first launch or after a server update. The launcher verifies file integrity before each session.

**Game crashes immediately**

Open the console (wrench icon → Copy All) and look for Java errors. Common causes:
- Not enough RAM — increase memory in server settings
- Incompatible optional mod — disable mods one by one in server settings

---

## Offline mode

Enable **Offline mode** in Settings → Behavior to skip authentication and file sync.

:::caution
Offline mode requires at least one prior online launch on the same server.
:::
