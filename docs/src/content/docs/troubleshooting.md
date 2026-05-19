---
title: Troubleshooting
description: Common issues and how to fix them.
---

## Where are the logs?

| | Path |
|---|---|
| Crash reports | `<data dir>/crash-reports/` |
| Session logs | `<data dir>/logs/launcher.log` |
| Console output | Wrench icon in sidebar → **Copy All** |

`<data dir>` is `%LOCALAPPDATA%\Nexira` (Windows), `~/Library/Application Support/Nexira` (macOS), or `$XDG_DATA_HOME/nexira` (Linux). See [Installation](/Nexira/installation/) for the full table.

:::tip[Reporting a bug?]
**Settings → Diagnostics → Create diagnostic bundle** packs redacted logs, crash reports, the action ring and system info into a single ZIP. The companion **Report on GitHub** button copies the ZIP path to clipboard and opens a pre-filled issue form. [Open a bug report directly →](https://github.com/Kitty-Hivens/Nexira/issues/new?template=bug_report.md)
:::

---

## Launcher won't start

**Windows -- crashes silently before the window appears**

Make sure you're running the official installer or portable ZIP from the [releases page](https://github.com/Kitty-Hivens/Nexira/releases/latest), not an older third-party build. Check `%LOCALAPPDATA%\Nexira\logs\launcher.log` for the actual crash stack.

**Linux -- AppImage won't run**

Install FUSE:

```bash
# Ubuntu/Debian
sudo apt install libfuse2

# Arch
sudo pacman -S fuse2
```

**Linux -- blank window or crash on Wayland**

Nexira ships a Liberica JDK runtime that handles Wayland through XWayland natively; no extra env vars should be needed. If the window still doesn't appear, check that the Skiko native dependencies are present:

```bash
# Arch
sudo pacman -S fontconfig freetype2 libglvnd

# Debian/Ubuntu
sudo apt install libfontconfig1 libfreetype6 libgl1
```

---

## Login issues

**"SSL certificate error" warning**

The SMARTYcraft API certificate sometimes expires before the operator renews it. Nexira shows an SSL bypass prompt with three grant durations:

- **1 hour** -- short outage, expect the cert to be fixed today
- **30 days** -- typical renewal cadence
- **Always (100 years)** -- if you've given up on upstream's TLS hygiene

Granted bypasses appear in **Settings → Network → SSL bypasses** and can be revoked at any time. Credentials are still only sent to the SMARTYcraft API, just over a connection where the certificate isn't validated.

**"Bad login" or "User not found"**

Check your credentials on [smartycraft.ru](https://www.smartycraft.ru). The launcher uses the same login as the website.

**2FA accounts**

Two-factor authentication is **not supported in Nexira**. The SMARTYcraft 2FA flow is not part of the documented protocol and the launcher cannot complete login on a 2FA-protected account. Disable 2FA on smartycraft.ru if you need to use Nexira.

---

## Game won't launch

**Files keep re-downloading every time**

Normal on first launch or after a server update. The launcher verifies file integrity (MD5) before each session.

**Game crashes immediately**

Open the console (wrench icon → Copy All) and look for Java errors. Common causes:

- Not enough RAM -- raise the memory cap in **Server Settings → RAM**
- Incompatible optional mod -- disable mods one by one in **Server Settings → Mods**

**Custom JVM flags break the launch**

The experimental **JVM Args Builder** (Settings → Experimental → JVM Args) lets you set custom GC, JIT, CDS and JFR flags. If a configuration breaks the launch, reset to defaults from the same screen.

---

## Network issues

**News feed empty / can't fetch server list**

Nexira routes SMARTYcraft traffic directly by default and falls back to the SOCKS proxy at `proxy.smartycraft.ru:58613` on connection failure. If both are blocked, **Settings → Network → Force proxy mode** sticks to the proxy from the start.

**Stale data dir warnings**

If you moved the data directory through **Settings → Data dir → Move**, the relocation applies on the next launcher start. The bootstrap config at `~/.nexira.conf` records the target; check that file if migration appears stuck.

---

## Offline mode

Enable **Offline mode** in Settings → Behavior to skip authentication and file sync.

:::caution
Offline mode requires at least one prior online launch on the same server (the launcher uses the cached file manifest from that session).
:::
