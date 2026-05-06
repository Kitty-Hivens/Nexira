---
title: Architecture
description: Module structure and key design decisions in Aura Launcher.
---

## Module structure

```
aura-launcher/
├── client-config      # Constants, AppConfig, BuildConfig
├── client-core        # Domain models, API services, interfaces
├── client-launcher    # DI wiring, file download, update, launch logic
└── client-ui          # Compose Multiplatform desktop UI
```

### client-config

App-wide constants: server URLs, timeouts, file paths, proxy config.  
Generates `BuildConfig` with version and build time via `gmazzo/buildconfig`.

### client-core

Pure business logic — no UI, no DI framework dependencies.

- `AuthService` — login, AES token decryption, SSL error detection
- `ServerRepository` — dashboard fetch, launcher hash update cycle
- `SkinRepository` — skin/cloak upload
- `HttpClientProvider` — switches between secure/insecure OkHttp client at runtime
- Domain models: `SessionData`, `FileManifest`, `SettingsData`, etc.

### client-launcher

Wires everything together with Koin DI.

- `FileDownloadService` — manifest flattening, parallel download, MD5 verification, extra.zip unpacking
- `LauncherService` + `GameCommandBuilder` — assembles JVM command line for 1.7.10 / 1.12.2 / 1.21.1
- `UpdateService` — GitHub Releases API, per-OS asset detection, SHA256 verification
- `CredentialsManager` — AES-256-GCM encryption with PBKDF2 key derivation
- `ProfileManager` — per-server instance profiles, favorites, last server
- `JavaManagerService` — downloads Bellsoft JDK bundles per Minecraft version

### client-ui

Compose Multiplatform desktop UI.

- `AppLayout` / `AppRoot` — root composition, window management, auto-login
- `DashboardScreen` — server grid, launch control panel
- `RightPanel` — login/account panel, news feed
- `TrayManager` — dorkbox/SystemTray wrapper
- `LauncherController` — launch pipeline state machine (`Idle → Prepare → Downloading → GameRunning`)

---

## Key design decisions

### HttpClientProvider instead of direct HttpClient injection

Repositories receive a provider that resolves the correct client on every call. This allows SSL bypass to take effect immediately without recreating Koin singletons.

### JNA version pinning

dorkbox/SystemTray 4.4 performs a hardcoded version check against JNA. JetBrains Runtime 25 bundles JNA 7.x, which fails this check. Solution: `resolutionStrategy.eachDependency` forces JNA to `6.1.6` globally, with `5.18.1` pinned explicitly in `client-ui` for Windows.

### AppImage manual assembly

Compose Multiplatform's built-in Linux packaging produces DEB/RPM. AppImage is assembled manually in CI using `jlink` for a minimal JRE and `appimagetool`, with `.desktop` and AppStream metainfo injected.

### GameCommandBuilder version configs

Each supported Minecraft version has an immutable `VersionConfig` — main class, tweak class, JVM flags, natives dir. NeoForge (1.21.1) requires module path separation (`-p`) and additional `--add-opens` flags for Java 21+.

### Protocol constants (`AppConfig.LEGACY_*`)

The auth salt, default launcher hash, default server id and protocol JAR descriptor in `AppConfig` were recovered from the decompiled official SMARTYcraft launcher: [`Kitty-Hivens/smrt-deco`](https://github.com/Kitty-Hivens/smrt-deco). The upstream is Proguard-obfuscated (`a.java`, `b.java`, …) and archived since April 2026, so any future protocol change will need to be re-derived from a fresh dump.
