<div align="center">
  <h1>Aura Launcher</h1>
</div>

<div align="center">

[![Last Release](https://img.shields.io/github/v/release/Kitty-Hivens/Aura-Launcher?style=for-the-badge&color=BB86FC&logo=github&logoColor=D9E0EE&labelColor=1E202B)](https://github.com/Kitty-Hivens/Aura-Launcher/releases/latest)
[![Docs](https://img.shields.io/badge/docs-online-BB86FC?style=for-the-badge&logo=astro&logoColor=D9E0EE&labelColor=1E202B)](https://kitty-hivens.github.io/Aura-Launcher/)
[![License](https://img.shields.io/badge/license-GPLv3-86dbd7?style=for-the-badge&logoColor=D9E0EE&labelColor=1E202B)](LICENSE)
[![Platform](https://img.shields.io/badge/Windows%20%7C%20Linux%20%7C%20macOS-supported-86dbce?style=for-the-badge&logoColor=D9E0EE&labelColor=1E202B)](#)

</div>

<div align="center">
  <h3>An unofficial launcher for <a href="https://www.smartycraft.ru">SMARTYcraft</a>.<br>Works on Windows, Linux and macOS — no manual Java setup required.</h3>
</div>

---

<details>
  <summary>Features</summary>

- All servers: 1.7.10, 1.12.2, 1.21.1 (NeoForge)
- Automatic file sync and integrity check before every session
- Auto-update with SHA256 verification
- Skin upload and preview
- Offline mode
- Custom themes and backgrounds
- System tray, optional mods per server
- Russian, English, German
</details>

<details>
  <summary>Installation</summary>

| Platform | Tier | File |
  |---|---|---|
| Windows | tier-1 | `AuraLauncher-*-Setup.exe` |
| Windows (portable) | tier-1 | `AuraLauncher-*-Windows-Portable.zip` |
| Linux | tier-1 | `AuraLauncher-*-x86_64.AppImage` |
| macOS Apple Silicon | tier-1 | `AuraLauncher-*-aarch64.dmg` |
| macOS Intel | community | `AuraLauncher-*-x86_64-community.dmg` |

→ [**Latest Release**](https://github.com/Kitty-Hivens/Aura-Launcher/releases/latest)

For detailed instructions see the [documentation](https://kitty-hivens.github.io/Aura-Launcher/).
</details>

<details>
  <summary>Platform support tiers</summary>

Aura is built and validated by a single maintainer on the platforms below.
Tier choice reflects how confidently each release is exercised before publish,
not the quality of the code path itself.

  - **tier-1 — supported.** Built in the release pipeline on every tag. Tested
    end-to-end on at least one machine before the release is cut. Bug reports
    are acted on directly. Covers Windows x86_64, Linux x86_64 (AppImage),
    macOS Apple Silicon (aarch64).

  - **community-tier.** Asset is shipped but the maintainer doesn't routinely
    boot it. Builds via a manual `workflow_dispatch` after the release is
    published, so the DMG appears on the release page with a delay (hours, sometimes days).
    Bug reports welcome and reviewed but turnaround depends on community
    contributors who own the platform. Covers macOS Intel (x86_64).

Why Intel macOS isn't tier-1: the macos-13 runner (last Intel image GitHub
maintains) sits in the free-tier queue for hours during US peak, which would
push every tier-1 release out by half a day if Intel were a hard dep. The
Intel install base in 2026 is small enough that this trade-off is reasonable.
The build is `*-community` named so the tier is obvious from the filename.

For platforms not listed (FreeBSD, Linux ARM, etc.) the launcher likely runs
under reasonable JVM availability but isn't tested. Patches welcome.
</details>

<details>
  <summary>Contributing</summary>

Forks and PRs are welcome. Code is GPL-3.0 — take it and do whatever.  
See [CONTRIBUTING.md](CONTRIBUTING.md) for details.
</details>

---

> ※ Not affiliated with or endorsed by SMARTYcraft.  
> ※ Protocol constants reverse-engineered from [`Kitty-Hivens/smrt-deco`](https://github.com/Kitty-Hivens/smrt-deco).
