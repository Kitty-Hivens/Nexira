# Changelog

All notable changes to Nexira (formerly Aura Launcher) will be
documented in this file. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

※ Each released entry opens with a short `### Highlights` block --
2-5 plain-English bullets summarizing what the user actually notices.
The launcher's in-app update dialog renders just the Highlights; the
detailed `### Added`/`### Changed`/`### Fixed`/`### Removed` sections
below are for the GitHub release page and CHANGELOG readers.

## [Unreleased]

## [2.3.0] - 2026-05-20

Rebrand release. The launcher is now called **Nexira**; the underlying
service it targets (SMARTYcraft) is unchanged, and so is the wire
protocol, the auth flow, and the file-sync semantics. Existing Aura
data is preserved through a mandatory migration UI on first Nexira
launch.

### Highlights
- **Aura is now Nexira.** Window title, executable, install dir, data
  dir, AppStream id, .desktop entry, GitHub repo and docs URL all
  change. SmartyCraft compatibility is byte-identical to 2.2.16.
- **Mandatory data migration on first launch.** When Nexira detects an
  Aura-era data directory it shows a full-screen modal with size /
  file count and a single "Migrate now" button. The copy runs with a
  determinate progress bar; on completion the launcher asks for a
  restart. The old Aura folder is left in place as a backup -- delete
  manually once you've confirmed Nexira loads your settings.
- **2FA accounts now cleanly rejected.** SMARTYcraft's two-factor
  flow is not part of the documented protocol and was never working
  reliably here. Nexira surfaces the limitation up-front instead of
  failing at game launch.

### Changed
- Window title / WM_CLASS / app bundle id all renamed:
  `AuraLauncher` -> `Nexira` (Windows / macOS install + dock name),
  `aura-launcher` -> `nexira` (Linux binary + .desktop slug).
- Default per-OS data directory:
  `%LOCALAPPDATA%\AuraLauncher` -> `%LOCALAPPDATA%\Nexira` (Windows),
  `~/Library/Application Support/AuraLauncher` -> `~/Library/Application Support/Nexira` (macOS),
  `$XDG_DATA_HOME/aura-launcher` -> `$XDG_DATA_HOME/nexira` (Linux).
- Environment-variable override: `AURA_DATA_DIR` -> `NEXIRA_DATA_DIR`
  (no fallback; the old name is no longer recognized).
- Bootstrap config moves from `~/.aura-launcher.conf` to `~/.nexira.conf`.
  Reads transparently fall back to the legacy file until the first
  write, so a user with a custom data-dir setting keeps working.
- AppStream id: `io.github.kitty_hivens.auralauncher` -> `dev.hivens.nexira`.
- GitHub repository: `Kitty-Hivens/Aura-Launcher` -> `Kitty-Hivens/Nexira`.
- Logback system properties: `aura.logs.dir` / `aura.sessionId` ->
  `nexira.logs.dir` / `nexira.sessionId`. Gradle puppet-mode prop
  `-PauraPuppetPort` -> `-PnexiraPuppetPort`; runtime system prop
  `-Daura.puppet.port` -> `-Dnexira.puppet.port`.
- Convention plugin id in `buildSrc`: `aura.packaging` -> `nexira.packaging`.

### Kept verbatim (compat / data preservation)
- `CredentialsManager.SALT` = `"Aura_v2_salt"` and `KEYRING_SERVICE` =
  `"io.github.kitty_hivens.AuraLauncher"`. Rotating these would
  invalidate every existing user's saved-credentials envelope and OS
  keyring entry; the underlying secret is the same, the brand is
  cosmetic.
- `PlatformPaths.legacyDataDirs` continues to point at the Aura-era
  and pre-2.3 directories so the migration UI can find old data.

## [2.2.16] - 2026-05-19

Bug-fix + size-cut release. The headline is a UI-freeze fix that bit
every user on every "Play" click — the tray library was making
blocking D-Bus calls on the EDT during launch state transitions,
holding up the whole window for seconds at a time. Alongside that,
the distribution size drops noticeably (~10% on AppImage / DMG,
similar on the Windows installer) thanks to a custom jlink + jpackage
pipeline that finally lands the flags (`--vm=server`, `--strip-debug`,
`--include-locales=en,ru,de`) that Compose Desktop's built-in
`nativeDistributions` block never exposed. Internal: the build
toolchain swaps from JetBrains Runtime to BellSoft Liberica, and the
packaging infrastructure moves into a `buildSrc/` convention plugin
so the AppImage shell script and the Windows / macOS jpackage path
share one configuration source.

### Highlights
- **No more freeze on Play click** — clicking "Play" used to lock the
  launcher window for several seconds while the system-tray library
  made blocking D-Bus calls on the UI thread. Tray status updates are
  now off the EDT entirely; the window stays responsive through the
  whole launch flow. Affects every Linux user under
  KDE / Hyprland / GNOME / Cinnamon.
- **Smaller download across every platform.** The custom jlink runtime
  drops the unused HotSpot VM variants (client + minimal, ~22 MB),
  trims three unused JDK modules (`java.sql`, `java.naming`,
  `java.net.http`), restricts locale data to en/ru/de, and strips
  debug info. Compared to 2.2.15: AppImage and DMG drop by ~10 MB,
  the Windows installer by a similar amount. Inner jlink compression
  intentionally not applied; outer LZMA (Inno Setup) and squashfs-zstd
  (AppImage) compress a raw runtime image more tightly than they can
  a pre-compressed one (measured locally: a zip-9 inner pass costs
  8 MB on the AppImage path and ~13 MB on the LZMA path).
- **/diag endpoints for puppet** (developer-facing) — the puppet HTTP
  control surface gains read-only diagnostic endpoints
  (`/diag/threads`, `/diag/jvm`, `/diag/actions`, `/diag/snapshot`)
  for automated profiling and freeze diagnosis. Off
  `Dispatchers.Swing` by design so they do not perturb what they
  measure. Available only in puppet builds (`-PauraPuppetPort=N`).

### Added
- `aura.packaging` convention plugin in `buildSrc/` — typed Gradle
  tasks (`customRuntime`, `emitAppImageProfile`,
  `customJpackageImage`, `customDmg`) consume a single source of
  truth in `client-ui/build.gradle.kts` for the jlink + jpackage flag
  set. Replaces the badass-runtime-plugin path (evaluated, rejected:
  plugin-apply conflict with Compose Multiplatform's `run` task plus
  `Task.project` usage that breaks the strict configuration-cache
  policy).
- Puppet `/diag/*` endpoints plus matching `dev-tools/puppet/diag-*.sh`
  CLI wrappers.

### Changed
- Build toolchain: JetBrains Runtime 25 → BellSoft Liberica 25. CI
  pins `JAVA_DISTRIBUTION=liberica` for reproducibility; local
  toolchain policy is vendor-loose so any JDK 25 on PATH works.
  Foojay-resolver-convention plugin in `settings.gradle.kts`
  auto-provisions a JDK 25 if missing.
- AppImage `jlink` invocation gains `--vm=server` and
  `--include-locales=en,ru,de` (with `jdk.localedata` added to the
  module set). Drops the previous `--compress=2`: the outer
  squashfs-zstd (AppImage) and LZMA2 (Inno Setup) compressors
  produce smaller artifacts when the inner runtime image is left
  uncompressed.
- Windows EXE and macOS DMG production switch from Compose Desktop's
  `createReleaseDistributable` / `packageReleaseDmg` to
  `aura.packaging`'s `customJpackageImage` + `customDmg`. Layout
  unchanged from Inno Setup's and DMG-host's perspective.
- `scripts/build-appimage.sh` reads jlink modules + flags from a
  generated `packaging-profile.sh` shell fragment
  (`emitAppImageProfile` task) so the same source of truth feeds
  every distribution surface.
- macOS bundle identifier corrected to `dev.hivens.auralauncher`.
  Apex domain is `hivens.dev`, so reverse-DNS leftmost component is
  `dev`, not `com`.

### Fixed
- **UI freeze during launch state transitions** — the system-tray
  library's D-Bus signal emission (used to update the tray menu /
  tooltip when launch state changes from Idle → Prepare → Sync →
  GameRunning) ran on the EDT via a synchronous
  `dbus_connection_flush`, blocking the AWT event queue for the
  duration of every native send. Moved to a dedicated sender thread
  in libtray `e5e6b5f`. The window now repaints, accepts clicks, and
  redraws correctly through every state transition.
- Three unused JDK modules trimmed from the runtime image (`java.sql`,
  `java.net.http`, `java.naming`); verified via bytecode scan that no
  code path in the bundle references them.

### Removed
- `AURA_WAYLAND_TRIAL` workflow (`trial-appimage.yml`) and the
  env-gated `-Dawt.toolkit.name=WLToolkit` jvmArg block. JBR-only
  experiment; Liberica does not ship `sun.awt.wl.WLToolkit`. Wayland
  sessions continue to work via XWayland.
- `-Dawt.appClassName=AuraLauncher` jvmArg + matching AppRun line.
  JBR-only honour; on stock OpenJDK the `Main.kt` reflection into
  `sun.awt.X11.XToolkit.awtAppClassName` is the real WM_CLASS path
  and works regardless of the jvmArg.
- `System.setProperty("jna.nosys", "true")` from `Main.kt` —
  dorkbox/JBR-era artifact, no longer relevant after the libtray and
  Liberica swaps.

## [2.2.15] - 2026-05-18

Network plumbing + UI responsiveness release. The "Force proxy mode"
toggle from 2.2.13 finally takes effect across every smartycraft.ru
request, not just the auth handshake -- skins, news images and pack
syncs now honour the user's choice and react to it without a
relaunch. Several "the launcher froze when I clicked X" reports trace
back to native `Desktop.open` / `Desktop.browse` calls running on the
Compose UI thread; every such call now dispatches to a daemon thread
so a wedged `xdg-desktop-portal` D-Bus can no longer hold up the
window. The integrity walk that hashes every file in a modpack now
emits progress while it works, so a cold launch on a 1000-file pack
no longer looks frozen for tens of seconds. Plus an architectural
sweep -- `LauncherController` moves to the right module, the chaos
subsystem hides behind a CompositionLocal, several long-standing
god-files and singleton patterns get cleaned up.

### Highlights
- **"Force proxy mode" actually does what it says** -- pre-fix the
  toggle only affected the auth handshake. Skins, news images and
  pack-file downloads stayed pinned to the SOCKS proxy regardless of
  the setting, so users in networks where `proxy.smartycraft.ru:58613`
  is unreachable saw login work but everything else silently fail.
  Every smartycraft.ru request now reads the toggle freshly per call;
  flipping it in Settings takes effect on the next request without a
  restart.
- **News strip can retry after network recovery** -- the news feed
  used to fetch exactly once on startup; a single failure stuck it
  in the empty state for the rest of the session. The empty state
  now has a Retry button that bypasses the in-memory cache, and the
  feed re-fetches automatically on a force-proxy or SSL-bypass
  toggle.
- **Click handlers don't freeze the launcher anymore** -- `Desktop.open`
  and `Desktop.browse` (Open folder, View on GitHub, Report on GitHub,
  news links, register button, ...) now dispatch to a daemon thread.
  A stuck `xdg-desktop-portal` D-Bus or misconfigured `xdg-open`
  on Linux can no longer hold up the EDT.
- **Integrity walk shows progress** -- when the launcher hashes every
  file in a 1000-file modpack before deciding what to download, the
  progress bar now advances visibly through that phase instead of
  freezing at 20% for tens of seconds.
- **Custom upstream version pin** -- new opt-in field in Settings
  (Experimental section) lets users override the version string the
  launcher sends to the upstream handshake, in case the upstream pins
  a newer version before the next Aura release ships. Persisted across
  restarts; applied without one.

### Added
- `SettingsData.mimicVersionOverride` + Experimental row in Settings
  with a toggle and revealed text field. `Main.kt` replays the persisted
  value on Koin start through `SettingsRestoreHook`; the Settings UI
  applies changes immediately via `Protocol.setMimicLauncherVersion`,
  so the override takes effect on the next protocol call without a
  relaunch. en / ru / de strings shipped.
- `NetworkState.bypassesState: StateFlow<List<SslBypassEntry>>` and
  `NetworkState.forceProxyState: StateFlow<Boolean>` -- UI sites
  subscribe via `collectAsState` instead of polling. The prior
  `produceState { ... delay(200ms) }` poll in `AppLayout` and
  `DashboardScreen` is gone.
- `LaunchLogEvent` + `LaunchError` + `PrepareStage` in
  `hivens.launcher.launch` -- semantic event channel that the UI
  drains into the console pane with localization at the UI layer.
- `LauncherControllerTest` in `client-launcher` -- constructor-injected
  controller is now testable without Koin; happy-path + offline + 2FA
  + non-zero exit covered.
- `SystemActions` helper -- `openFolder` / `openFile` / `openUrl`
  helpers that dispatch every `java.awt.Desktop` call to a daemon
  thread.
- `IServerListService.refresh()` -- bypasses the in-memory cache for
  user-driven retries (news strip + dashboard "Retry" button).
- `IFileDownloadService.processSession` gains a `verifyUI: ((Int, Int) -> Unit)?`
  callback for the integrity walk so the controller can map MD5 progress
  onto the SYNC stage's 0.2..0.7 sub-range.
- `ProcessLogHandlerTest` -- 12 cases covering the new prefix-aware
  classifier.

### Changed
- `LauncherController` and `LaunchState` move from `client-ui/logic` to
  `client-launcher/launch`. The controller is now constructor-injected
  (no `KoinComponent`), depends only on `client-core` interfaces plus
  the shared application scope, and emits semantic events instead of
  reaching into `client-ui` for strings and console state. A new
  `LaunchLogCollector` Composable in `client-ui` drains the event flow
  into `GameConsoleService` with localization done at the UI layer.
- April Fools subsystem hides behind `AprilFoolsLifecycle` (a
  CompositionLocal-routed interface) with `NoOpAprilFools` as the
  production fallback. Every consumer (14 files) now reads
  `LocalAprilFools.current` instead of touching the calendar object,
  `ChaosState`, or `FloatingButton` directly. Lays the groundwork for
  a compile-time gate via SPI in a follow-up release.
- `RightPanel.kt` god-file (809 LOC) splits into `LoginPanel.kt`,
  `AccountPanel.kt`, `CompactNewsFeed.kt`. Pure file move; no
  behaviour change.
- Process-lifetime coroutine scope is now a single Koin-managed
  `single<CoroutineScope>(createdAtStart = true)` cancelled by
  `AppCoroutineScopeHook` on JVM shutdown. `LauncherController` and
  tray-launch flow share the same scope -- pre-fix the controller
  had its own scope that no shutdown hook could reach, so a SIGTERM
  mid-launch could orphan the spawned game process.
- `AutoSyncService.serverStates` + `overallState` collapse into a
  single `Snapshot` StateFlow; consumers always wanted both together.
- `object I18n { ... }` mutable global removed -- after the
  controller move the only remaining consumer (`LaunchLogCollector`)
  is `@Composable` and reads `LocalStrings.current` directly with
  `rememberUpdatedState` for the non-Composable lambda inside its
  collector.
- `KoinJavaComponent.get<>()` escape hatch in `Main.kt` replaced by
  `SettingsRestoreHook` (createdAtStart). Persisted force-proxy and
  mimic-version values now restore through the DI graph instead of a
  post-startKoin escape.
- `CrashReporter` and `GameConsoleService` were singletons that read
  `PlatformPaths.system()` directly; both are now regular classes
  with `PlatformPaths` injected, so a mid-session data-dir migration
  routes their writes to the new directory.
- Data classes (`ServerProfile`, `InstanceProfile`, `OptionalMod`,
  `SettingsData`) flip every `var` field to `val`; mutation sites
  switch to `.copy(...)` or named-arg constructor calls.
- `compareVersions` (UpdateService) ranks prerelease suffixes via
  natural-order tokenisation -- `rc10 > rc2` is correct now, where
  the prior lex-only compare ranked them backwards.
- Process console classifier no longer false-positives on lines
  containing "no warnings", "errorless", "swarming", etc. -- a
  structured `[Thread/LEVEL]` prefix wins authoritatively when
  present, with a word-boundary fallback for unframed lines.
- Gradle wrapper bumped to 9.5.0.

### Fixed
- Force-proxy toggle was effectively a no-op for skins, news images
  and pack-file downloads -- the proxy was baked into the OkHttp
  client at Koin construction. The default smartycraft
  `HttpClientProvider` and Coil's image fetcher now route per-request
  based on `NetworkState`.
- News strip would lock into the empty state after a first-fetch
  failure for the rest of the session.
- `Desktop.open` / `Desktop.browse` calls (open logs folder, open
  crash reports, view release on GitHub, register link, news item
  links, ...) could freeze the UI for seconds when the OS handler
  (xdg-open, `xdg-desktop-portal`, Windows default-handler config)
  was misbehaving.
- "Move data directory" picker in Settings rendered without the
  styled title on some Linux portal backends because the call was
  missing `dialogSettings`; aligned with the picker call in Profile /
  Server Settings.
- `SkinManager` used to compose disk-cache filenames from the raw
  nickname; `safeCacheBase` now sanitises so a hostile nickname can't
  escape `skinCacheDir`.
- 2FA-expired launches reported a generic "Error: re-login required"
  through the `Internal` catch-all instead of the dedicated
  `TwoFactorExpired` reason; UI now renders the correct, actionable
  message.
- `ManifestProcessorService` patched `mod.id` / `mod.jars` on a
  data-class instance after deserialisation. After the `var -> val`
  pass it builds a `decoded.copy(...)` defaulting layer instead.

### Removed
- `client-ui` dependency on `kotlinx-coroutines-slf4j` (the MDC
  context now lives with the producer in `client-launcher`).
- `AppLocale.detectSystem()` -- dead code after the `I18n` global
  was dropped (SettingsData hard-codes the default locale, which
  shadowed system detection anyway).

## [2.2.14] - 2026-05-17

Three boot-path hotfixes plus a settings cleanup. The launcher.log
ended up next to the binary on first launch because LoggerFactory
captured the working-directory default before `PlatformPaths` was
resolved; on Linux distros without libsecret, ProGuard stripped the
dbus-java service provider that `LinuxLibsecretKeyringStorage` walks
to find a transport; and libtray's upcall stubs got renamed away by
ProGuard rather than kept by name. Plus the long-broken
`startInTray` setting is gone -- no in-launcher way to invoke it,
no real use case, and it bricked first-time users into "where did my
window go" support tickets.

### Fixed
- `launcher.log` now lands in the platform `logsDir`
  (`%LOCALAPPDATA%\AuraLauncher\logs` / `~/Library/Application Support/.../logs`
  / `$XDG_DATA_HOME/aura-launcher/logs`) on the very first init,
  not in `./logs/` next to the binary. `PlatformPaths.system()` is
  now resolved before the first `LoggerFactory.getLogger()` call so
  logback's `${aura.logs.dir}` substitution sees the correct value.
- libtray's `MethodHandles.Lookup` upcall stubs are explicitly
  `-keep`'d in the ProGuard config so the tray daemon can call back
  into Aura after R8 rename.
- `org.freedesktop.dbus.**` classes are `-keep`'d so ServiceLoader
  still finds `NativeTransportProvider` after shrinking. Linux
  distros without libsecret fell back to D-Bus discovery and crashed
  on `ServiceConfigurationError`; the keep gate lets discovery
  succeed and the keyring fallback path activate normally.

### Removed
- `SettingsData.startInTray` and the corresponding Settings UI
  toggle. The launcher always starts visible; tray remains the
  dock-style fallback for close-while-game-running.

## [2.2.13] - 2026-05-15

Security, concurrency, and platform-completion release. The auto-updater
now refuses to install bytes it cannot integrity-verify against a manifest
hash. The mod-pack file sync gained a sample-existence sanity gate that
catches the case where the manifest cache says "synced" but the actual
files are gone (the bug behind the empty-classpath crash on cold launches).
Two-factor authentication finally works in the launcher itself instead of
locking out 2FA-enabled accounts. macOS Apple Silicon joins Linux and
Windows on the OS-keyring path. Conduit pillar — the network-layer
refactor — is feature-complete; the `Network.BASE_URL` constant is no
longer reached by production code.

### Highlights
- **TOTP 2FA login** — accounts with two-factor authentication enabled
  in SmartyCraft can now sign in directly from Aura. A 6-digit code
  prompt appears after the password step; wrong codes re-prompt
  inline; expired sessions surface a clear "log in again" message
  instead of getting stuck in a verify loop. Russian / English /
  German strings shipped.
- **Auto-update refuses unverified installers** — every released asset
  now requires a SHA-256 entry in `release-manifest.json` before the
  launcher will install it. The pre-fix path silently treated an empty
  hash as success, which would have driven arbitrary bytes through the
  updater if anyone had edited a release page out from under it. Older
  releases that pre-date the manifest convention require manual
  reinstall — the auto-updater will refuse them rather than guess.
- **Cold-launch reliability fixes** — three classes of "click Play,
  game dies" reports addressed: (a) the manifest cache no longer lies
  when the client directory has been wiped between syncs (e.g. after a
  data-dir move or manual `rm`); (b) the natives-folder validity gate
  now requires the actual `lwjgl` library, not just any `.so` file
  (jinput-only directories used to pass and crash the game with
  `UnsatisfiedLinkError`); (c) `mods/*.jar` files are spot-checked
  for ZIP integrity even when their MD5 matches, catching the rare
  corrupt-bytes-with-correct-hash case that NeoForge would otherwise
  surface as "invalid CEN header" mid-launch.
- **macOS keyring (Apple Silicon)** — the third platform on the OS
  keyring path, after Linux libsecret and Windows DPAPI. Passwords
  and access tokens land in the user's login Keychain via the modern
  `SecItem*` API (Project Panama bindings to Security.framework).
  Falls back to the per-machine AES-GCM file when Keychain isn't
  reachable, identical to the Linux/Windows flow.
- **macOS Intel as community-tier** — Apple Silicon stays tier-1 (built
  on every release tag); Intel macOS now ships asynchronously via a
  manual `workflow_dispatch` build and is named
  `*-x86_64-community.dmg` so the support shape is obvious from the
  filename. The README has a new "Platform support tiers" section
  spelling out what tier-1 vs community means.

### Added
- `hivens.launcher.security.MacOSKeychainStorage` — Project Panama
  bindings to `Security.framework`'s `SecItem*` family
  (`SecItemAdd` / `SecItemCopyMatching` / `SecItemUpdate` /
  `SecItemDelete`) plus CoreFoundation marshalling for
  `kSecClass` / `kSecAttrService` / `kSecAttrAccount` / `kSecValueData`
  constants. Plugged into `KeyringStorageFactory.system()` for
  `os.name` containing `mac` or `darwin`.
- `hivens.core.api.TwoFactorRequiredException` — typed subclass of
  `AuthException` that the login flow throws when the server returns
  `TWOAUTH`. Carries the `uid` from the TWOAUTH response so the
  follow-up `twoauth(uid, login, code)` call can sign correctly.
- `IAuthService.completeTwoFactor(username, password, serverId, uid, code)`
  — sends the verification code, then promotes the cached TWOAUTH
  login response to `SessionData` directly when complete (uuid +
  playername + session populated) or falls through to a single
  re-login when the cached fields are sparse. Detects the
  re-login-also-returns-TWOAUTH loop and gives up with
  `TWO_FACTOR_EXPIRED` instead of pinning the user to a verify
  button that can never satisfy the server.
- `hivens.ui.components.ConfirmCodeDialog` — Compose 6-digit input
  with monospace styling, paste-friendly digit-only filter, inline
  error for `WRONG_CODE`, dismiss-on-cancel preserves credentials.
  i18n `auth2fa*` keys in EN / RU / DE.
- `hivens.launcher.platform.ServerNameValidator` — central whitelist
  (`[A-Za-z0-9._-]+` plus explicit rejection of `.`, `..`, and
  `..`-containing strings) used by `PlatformPaths.clientDir` and
  `ManifestCache.sanitize` so server-supplied identifiers can't
  escape the per-server data dir.
- `hivens.launcher.network.ServerProtocolConfig` reaches the last four
  `Network.BASE_URL` call sites (`ServerListService`,
  `GameCommandBuilder`, `RightPanel` register / news links) via DI
  / `koinInject`. Production code no longer references the const
  directly — only `Network.kt` itself does.
- `FileDownloadDiskIntegrationTest` — disk-level integration harness
  for `FileDownloadService.processSession` against a sandbox tempdir
  with MockEngine HTTP. Covers happy path, idempotent re-sync,
  corruption recovery, missing-file recovery, ProtectedPaths
  preservation, upstream HTTP failure, and the disk-wipe-between-syncs
  scenario from the manifest-cache bug.
- `ProfileManagerTest` — new test class plus three unit-test classes
  for the cache concurrency fixes (`ServerListServiceTest`,
  `SettingsServiceTest`, `LauncherHashCacheTest`).
- `ZipUtilsTest` — coverage for the symlink-rejection and
  Zip-Slip paths.
- `ClasspathProviderTest` — Linux / Windows / macOS native filtering
  + the unknown-OS defensive default.
- `.github/workflows/build-macos-x86_64-community.yml` — manual
  `workflow_dispatch` workflow that builds the Intel macOS DMG on
  `macos-13` against an existing tag and uploads to the matching
  release as `AuraLauncher-<version>-x86_64-community.dmg`.
- README "Platform support tiers" collapsible section explaining
  what tier-1 (Windows, Linux x86_64, macOS Apple Silicon) and
  community-tier (macOS Intel) mean in terms of validation SLA.

### Changed
- `UpdateService.checkForUpdate` now requires
  `release-manifest.json` to publish an SHA-256 for the asset before
  it constructs a `LauncherUpdate`. The legacy markdown-table
  fallback is no longer wired into the cold path; the parser stays
  in `extractChecksum` for an out-of-band recovery flow but doesn't
  gate installs.
- `verifyChecksum` returns false on blank input — defense in depth
  at the install boundary so a stale cached `LauncherUpdate` or
  future code path can't bypass the gate.
- `ManifestCache.isClean` accepts an optional disk-sanity-check
  lambda. `FileDownloadService.processSession` passes a 20-entry
  spot-check that forces a full integrity walk when the cache
  reports clean but the on-disk files are missing.
- `EnvironmentPreparer.isFolderValidForOs` matches on filenames
  containing `lwjgl` (case-insensitive) instead of any file with the
  platform's native extension. `liblwjgl.so` + `liblwjgl64.so` (LWJGL 2
  64-bit), the LWJGL 3 module split, and `lwjgl.dll` (Windows) all
  pass; jinput-only directories no longer do.
- `FileDownloadService.isFileMissingOrChanged` adds a JarFile open +
  central-directory walk for `mods/*.jar` files that pass MD5. Scoped
  to `mods/` because that's the corruption hot zone; `libraries/`
  skips the scan to keep cold-start fast.
- `ClasspathProvider` drops `*-natives-{otherOs}.jar` entries from
  the JVM classpath (server manifests ship every platform's
  classifier; pre-fix the launcher loaded all of them).
- `PlatformPaths.clientDir(assetDir)` requires the assetDir to pass
  `ServerNameValidator` before resolving — a malicious manifest can
  no longer point the client directory outside the data dir on
  filesystems where `Path.resolve` would have accepted the input.
- `ZipUtils.unzip` and `JavaManagerService.unzip` switched from
  `java.util.zip.ZipInputStream` to commons-compress `ZipFile` so
  the central-directory unix-mode bits are visible. Symlink entries
  are rejected before any byte is written. `JavaManagerService.untargz`
  rejects symlinks, hard links, FIFOs, and device-node entries.
- `DataDirMover` and `DiagnosticBundle` walks now skip symlinks
  consistently with the ZIP/TAR family.
- `ServerListService`, `SettingsService`, and `LauncherHashCache`
  use proper synchronization for cache state — fetchDashboardData
  single-flights through a tracked in-flight future, settings
  reads / writes go through a monitor lock, and refresh-attempt
  counting uses an atomic CAS so the cap is exact under contention.
- `ProfileManager.save()` writes through a temp file + atomic move
  with `writeLock` serialization, so concurrent saves and crash-mid-
  write can't leave torn JSON on disk. `toggleFavorite` flip is now
  atomic via `Set.add`'s boolean return.
- Background flows in `Main.kt` (tray-launch, AutoSync) moved off
  `GlobalScope.launch` onto a process-lifetime
  `applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)`
  with a JVM shutdown hook. In-flight network sockets and file
  handles get released on launcher exit instead of being orphaned.
- `GameCommandBuilder` throws a recovery-hinted
  `IllegalStateException` when the NeoForge boot modules
  (securejarhandler / bootstraplauncher / ow2/asm / jarjar) are
  missing from the classpath, so the user sees a launcher dialog
  instead of a Java module-resolution crash at game startup.
- `ChannelRouter.isFallbackable` simplified to a single
  `is IOException` check — the previous explicit per-subclass `when`
  arms were unreachable.
- `SkinManager`'s in-memory caches are now LRU-capped at 64 entries
  per skin orientation. Long sessions with many viewed players no
  longer accumulate `ImageBitmap` GPU texture memory until OOM.
- `SingleInstance.writeShowSignal` uses `Files.createFile` with
  `FileAlreadyExistsException` swallowing instead of a TOCTOU
  `if (!exists) createFile()` race.

### Removed
- `Network.FORCE_HTTP1_FOR_SMARTYCRAFT` and
  `applySmartycraftProtocols()` — empirically confirmed obsolete; the
  modern okhttp + upstream proxy negotiate HTTP/2 cleanly direct and
  proxied.
- `LiveSmokeTest` + `smoke-daily.yml` workflow + `:smokeTest` Gradle
  task. The pure-MockEngine + integration-harness coverage is
  sufficient and the live smoke probe was getting flaky against the
  real upstream during outages.

### Fixed
- Auto-updater silently installing un-checksummed assets (#186).
- ZIP / TAR archive extractors accepting symlink entries that bypass
  the `startsWith(destDir)` Zip Slip check (#187).
- `PlatformPaths.clientDir(assetDir)` accepting unvalidated server
  identifiers (#188).
- Cache state in `ServerListService` / `SettingsService` /
  `LauncherHashCache` racing under concurrent reads / writes (#189).
- `ProfileManager` toggleFavorite TOCTOU and torn-write on save (#190).
- `GlobalScope.launch` orphaning sockets / file handles past launcher
  shutdown (#191).
- `ManifestCache.isClean` returning true when the client directory
  was wiped after the cache was marked clean (#184) — the
  empty-classpath cold-launch crash on RPG.
- `EnvironmentPreparer.isFolderValidForOs` accepting jinput-only
  directories as valid LWJGL natives (#185) — the
  `UnsatisfiedLinkError: lwjgl64` on first launch of un-installed packs.
- `FileDownloadService` not catching corrupt-but-MD5-matching
  jars in `mods/` before launch (#169).
- `RightPanel` register and news links plus `ServerListService`
  news image URL plus `GameCommandBuilder` `-D` flags reaching the
  deprecated `Network.BASE_URL` constant — all migrated to
  `ServerProtocolConfig.baseUrl` injection.

## [2.2.12] - 2026-05-14

Security and platform-completion release. Passwords and login tokens now
live in your OS keyring instead of an AES-GCM file (Vault — Linux libsecret
and Windows Credential Manager wired up; macOS pending). The "accept SSL
warning" flow stops being all-or-nothing: each bypass is per-host and
expires. Bridge pillar gets the missing UI for moving the data directory
without env-var hackery. macOS finally ships proper dual-architecture
DMGs with a real app icon. Plus a continuous AppImage portability check
in CI to catch "works on my distro" regressions before users do.

### Highlights
- **OS keyring integration** (Vault): launcher credentials now persist
  to GNOME Keyring / KWallet via libsecret on Linux and to Credential
  Manager (DPAPI) on Windows. Falls back to a per-machine AES-GCM file
  if no keyring is reachable, so nothing breaks on minimal desktops or
  headless installs. Both the password and the access token are
  protected — previously only the password was. macOS keyring impl is
  the next chunk.
- **Per-host SSL bypass with expiry**: when you accept a certificate
  warning, the bypass is now scoped to that host and ends when you say
  it does — session-only by default, with optional 1 hour / 1 day /
  7 days. Previous behaviour granted "trust every HTTPS call this
  process makes" until the launcher restarted. Settings → Network lists
  every active bypass with a Revoke button.
- **Move data directory** without touching `AURA_DATA_DIR`. Settings →
  Data directory → pick a new location → "Quit now" or schedule for
  next launch. The picker uses your desktop's native dialog
  (xdg-desktop-portal on Linux, AppKit on macOS, Win32 on Windows)
  instead of the Swing JFileChooser that looked broken on Hyprland and
  several KDE themes.
- **macOS dual-architecture DMGs** — separate builds for Apple Silicon
  (`*-aarch64.dmg`) and Intel (`*-x86_64.dmg`). Auto-updater now reads
  `os.arch` and downloads the correct one; previously the first DMG
  asset wins, which on a dual-arch release produced a 50/50 wrong-arch
  install. Plus a proper `.icns` app icon — the default Compose K-folder
  placeholder is gone.
- **Daily AppImage portability check**: a CI matrix downloads the latest
  released AppImage on Fedora / Arch / Debian-stable containers and
  verifies the app actually starts. Catches glibc / GTK / Skiko-loader
  regressions on distros the maintainer doesn't run day-to-day.

### Added
- `hivens.core.security.IKeyringStorage` interface in `client-core` —
  contract for `store(account, secret)` / `retrieve(account)` /
  `clear(account)` / `isAvailable()`. Stays free of any platform
  dependency so the per-OS impls can use Project Panama bindings
  without leaking into shared code.
- `hivens.launcher.security.LinuxLibsecretKeyringStorage` — Project
  Panama bindings to `libsecret-1.so.0`. Stores credentials under
  schema name `app.aura.launcher` with `account` attribute. Detects
  daemon availability via a write-and-clear probe (the historical
  "clear returns FALSE on no-match" semantics make a read-only probe
  unreliable per libsecret docs).
- `hivens.launcher.security.WindowsCredentialManagerKeyringStorage` —
  Project Panama bindings to `advapi32` (`CredWriteW` / `CredReadW` /
  `CredDeleteW` / `CredFree`). Targets `CRED_TYPE_GENERIC` with
  `LOCAL_MACHINE` persistence. Account names are namespaced
  `app.aura.launcher:<account>` to avoid colliding with other tools.
- `hivens.launcher.security.NoOpKeyringStorage` — explicit "no native
  store" sentinel returned by `KeyringStorageFactory.system()` when
  the host has no reachable keyring; `CredentialsManager` then routes
  to its AES-GCM file fallback.
- `hivens.launcher.security.KeyringStorageFactory.system()` — picks
  the impl by `os.name`, probes `isAvailable()` once, caches the
  decision for the JVM lifetime. Wraps construction in `runCatching`
  so an `UnsatisfiedLinkError` from missing libsecret can't kill the
  launcher startup.
- `hivens.core.security.SslBypassEntry` — `host` + ISO-8601 `expiresAt`,
  serialised to `ssl-bypasses.json` in the data directory.
- `hivens.launcher.NetworkState.bypassFor(host)` /
  `grantBypass(host, until)` / `revokeBypass(host)` /
  `listBypasses()` — replace the prior `sslBypassEnabled: Boolean`.
  Existing call sites that just need a yes/no for a request now ask
  by host instead of reading a global flag.
- `hivens.launcher.platform.BootstrapConf` — flat `key=value` file at
  `<user.home>/.aura-launcher.conf`, read before `PlatformPaths` is
  available. Used to persist the "move data dir to X on next launch"
  decision since the in-data-dir config can't reference where the data
  dir is moving to.
- `hivens.launcher.platform.DataDirMover.schedule(src, target)` /
  `applyPending()` — schedule-on-restart pattern: the move runs at next
  startup before any consumer has opened a handle in the old location,
  avoiding "directory in use" errors on Windows.
- Settings → Network section: lists every active SSL bypass (host +
  remaining time) with a Revoke button per entry. New i18n keys
  `settingsNetworkSection`, `settingsBypassListEmpty`,
  `settingsBypassRevoke`, `settingsBypassExpiresIn`,
  `settingsBypassSessionOnly` (EN / RU / DE).
- Settings → Data directory section: shows the resolved current path,
  a Choose button that opens the native directory picker, and a
  Quit-now confirmation. New i18n keys `settingsDataDirSection`,
  `settingsDataDirChoose`, `settingsDataDirSchedulePending`,
  `settingsDataDirQuitNow` (EN / RU / DE).
- SSL bypass duration picker on the warning dialog: session-only
  (default) / 1 hour / 1 day / 7 days. New i18n keys
  `sslBypassDurationLabel`, `sslBypassDurationSession`,
  `sslBypassDurationHour`, `sslBypassDurationDay`,
  `sslBypassDurationWeek` (EN / RU / DE).
- `resources/icons/icon.icns` — proper macOS icon generated from the
  existing 256/512 PNG sources via `png2icns`. Wired into the Compose
  Desktop `nativeDistributions { macOS { iconFile.set(...) } }` block.
- `.github/workflows/appimage-portability.yml` — daily cron + manual
  dispatch. Pulls the latest release AppImage onto Fedora 40 / Arch /
  Debian stable containers, runs it under Xvfb, and asserts the
  "Display: toolkit=" log line is emitted before timeout.
- `.github/workflows/smoke-daily.yml` — daily live smoke probe against
  the real upstream so we catch wire-protocol drift on SMARTYcraft's
  side without waiting for a release attempt.
- `.github/workflows/trial-appimage.yml` — opt-in WLToolkit trial build
  workflow for the Wayland-Native investigation.
- Review-gate job at the top of `build_release.yml`: blocks the release
  pipeline unless the merge-base PR has either an `APPROVED` review or
  a Codex / Qodana engagement record. Designed for solo-maintainer
  branch protection (count=0) where a second human reviewer isn't
  available — automation engagement counts as a weak signal.
- `LiveSmokeTest` + `@SmokeTest` JUnit Tag, plus `:client-launcher:smokeTest`
  Gradle task. Runs against the real upstream; gated behind the tag so
  the default `:test` run skips it.
- `LinuxLibsecretLiveProbeTest` (`@Tag("live-keyring")`) +
  `LiveWindowsKeyringProbeTest` (`@Tag("live-windows-keyring")`) — opt-in
  end-to-end tests that exercise the real OS keyring on machines that
  have one configured. Skipped by default `:test`.
- `JavaManagerServiceTest` (22 cases): MC-version → Java-major mapping,
  os/arch → BellSoft URL matrix, BellSoft archive layout discovery,
  and zip-slip rejection.
- `FileDownloadServiceTest` (18 cases): path normalisation against the
  recognised root list, manifest flattening across nested directories,
  MD5 computation against RFC 1321 vectors, and the
  `isFileMissingOrChanged` predicate (including the `"any"` sentinel
  and ProtectedPaths short-circuit).
- `EnvironmentPreparerTest` (15 cases): `os.name` → LWJGL classifier,
  per-platform native-extension presence check, and `flattenNatives`
  hoisting from nested directories.
- `CredentialsManagerTest`, `NetworkStateTest`, `BootstrapConfTest`,
  `DataDirMoverTest`, `KeyringStorageFactoryTest`,
  `NoOpKeyringStorageTest` — unit coverage for the new Vault and
  Bridge surfaces.
- `docs/src/content/docs/dev/wayland-investigation.md` and
  `scripts/wayland-probe.sh` — documents the Wayland-Native probe
  result (frozen pending upstream Skiko Wayland surface acquisition)
  and the re-trigger criteria.

### Changed
- `CredentialsManager` (v3 → v4 schema): keyring-primary with file
  fallback for both `password` and `accessToken`. Each value carries
  a separate "stored in keyring" flag so a partial migration (keyring
  lost one entry) recovers cleanly per-field instead of corrupting
  the whole credential. Existing v3 files are read once and migrated
  forward; the v3 file is removed only after successful re-encrypt.
- `NetworkState.sslBypassEnabled: Boolean` removed in favour of the
  per-host model. Every consumer (`HttpClientProvider.current` and the
  warning-dialog flow) updated to ask by host. The persisted
  `ssl-bypasses.json` lives next to other config in the data directory.
- `UpdateService.findAssetForCurrentOS` now reads `os.arch` and
  prefers `*-aarch64.dmg` on ARM hosts, `*-x86_64.dmg` on Intel hosts.
  Falls back to the legacy single-`*.dmg` pattern for releases produced
  before the dual-arch matrix existed (pre-2.2.12).
- `build_release.yml` macOS leg converted to a 2-job matrix:
  `macos-latest` (aarch64) + `macos-13` (x86_64), each producing a
  per-arch artifact and uploaded with an arch suffix. Per-arch artifact
  names are required because actions/upload-artifact v4 refuses
  same-name uploads from parallel jobs.
- `build_release.yml` review-gate widened to accept Qodana-scan
  completion as a weak signal in addition to APPROVED reviews and
  Codex engagement, so a real CI gate held when the primary reviewer
  was rate-limited.
- Local test runs cap `maxParallelForks` to 2 (was: number of cores)
  and JVM heap to 512 MB (was: 1 GB). CI is unaffected — it still uses
  the full core count and 1 GB heap. Triggered by local development on
  laptops where the test suite was saturating the CPU.
- `Settings → Move data directory` swapped from `JFileChooser` to
  filekit's native directory picker. JFileChooser ignored the system
  GTK theme and rendered with the Metal look-and-feel on Wayland
  compositors that don't have a Swing GTK bridge.

### Fixed
- Auto-updater on macOS could install the wrong architecture's DMG
  because `findAssetForCurrentOS` returned the first matching `.dmg`
  asset regardless of arch. Now arch-aware (see above). Existing
  pre-2.2.12 single-asset releases keep working through the legacy
  fallback branch.
- `AppRun` script in the AppImage was not propagating
  `AURA_WAYLAND_TRIAL` into the JVM args, so the trial flag set in
  the user's environment never reached `Main.main`. Fixed by
  forwarding the env var explicitly.

### Removed
- Phantom `-Dwayland.debug.children=true` JVM arg from
  `compose.desktop.application.jvmArgs`. The property exists nowhere
  in OpenJDK or Skiko; it was a misremembered name and a no-op.
- `NetworkState.sslBypassEnabled: Boolean` flag and its setter (see
  Changed). No call site remains; existing `ssl-bypass-enabled` keys
  in older state files are ignored.

## [2.2.11] - 2026-05-12

Infrastructure-heavy release focused on debuggability when something goes
wrong: a proper logging pipeline (Pulse), a one-click diagnostic bundle
(Beacon), the actual fix for the KDE/GNOME tray hover-title bug, and a
better unauthenticated dashboard state. Plus three audit-driven fixes
that catch regressions before users see them.

### Highlights
- **Centralised logging pipeline** (Pulse): launcher now writes
  structured rolling log files to the platform-correct data directory
  — `launcher.log`, `network.log`, `game.log` and `crash.log`, each
  with size + age caps. Game stdout/stderr persists automatically (no
  more "I forgot to save the console before the crash"). Crash forensics
  survive 30 days in `crash.log` even when active logs roll faster.
- **Per-launch tagging in logs**: every line carries
  `[sessionId/launchId]` — shipping a 200 MB log dump for support and
  needing only the last Play attempt? `grep launchId=abcd1234 *.log`
  slices to that one launch.
- **Token / password / UUID redaction** before any log line hits disk
  or the in-app console — screenshots and copy-pastes from the console
  for support are safe to share without manually scrubbing the
  `accessToken=...` lines.
- **One-click diagnostic bundle** (Beacon): Settings → Diagnostics →
  "Create diagnostic bundle" → ZIP with system info, the action history
  ring, all redacted log files, and every crash report — open the
  containing folder so you can attach the file to a support message
  in one motion.
- **"Report on GitHub" buttons** on the crash dialog and next to the
  diagnostic-bundle button — opens a browser at a pre-filled
  `github.com/issues/new` URL with the crash report (or a body asking
  you to drag-attach the bundle ZIP) already in the editor. Nothing
  leaves your machine until you review and click Submit on github.com;
  the launcher itself never POSTs anything. Designed as the principled
  alternative to telemetry — convenient for both sides without a
  phone-home codepath in the binary.
- **Action history ring buffer** behind the scenes: the last 64
  user/lifecycle events with timestamps. Replaces the old
  `lastAction = "..."` (one global string, only ever the most recent
  thing). Crash reports now include the full trail leading up to the
  crash, not just the last entry.
- **KDE/GNOME tray hover now actually says "Aura Launcher"** instead
  of "SystemTray". The previous tooltip-removal in 2.2.10 didn't fix
  the underlying cause — AppIndicator's hover text comes from the
  constructor argument to `SystemTray.get()`, not from `setTooltip()`.
- **Sign-in screen no longer shows a vacant spinning indicator**:
  when the launcher is waiting on user login, the main panel now
  shows an explicit "Sign in to see servers" message with a hint
  pointing at the right-side login form. Previously, both the brief
  startup-loading state AND the stable unauthenticated state rendered
  the same tiny spinner, making it look like servers were forever
  trying to load.

### Added
- `client-ui/src/desktopMain/resources/logback.xml` — central logging
  config with four rolling-file appenders. Output dir resolves from
  the `aura.logs.dir` system property set in `Main.main()` before the
  first `LoggerFactory.getLogger()` call. `AURA_DATA_DIR` override and
  per-OS data-dir layout flow through automatically.
- `kotlinx-coroutines-slf4j` dependency for `MDCContext`. Used in
  `LauncherController.launch()` so the per-launch `launchId` propagates
  through every coroutine dispatcher hop downstream (FileDownloadService,
  LauncherService, etc.) without each component having to set MDC itself.
- `hivens.core.logging.Redactor`: pure-function redactor masking
  `accessToken` / `password` / `Bearer` / `sessionToken` / `refreshToken` /
  `authToken` / `apiToken` / 8-4-4-4-12 UUIDs. 10 unit tests cover
  idempotency, case-insensitivity, multi-value lines, no-op on clean text.
- `hivens.ui.logging.RedactingMessageConverter` — custom logback `%rmsg`
  conversion word that replaces every appender's `%msg` so disk never
  carries raw credentials, even momentarily.
- `hivens.core.diag.ActionRing`: thread-safe bounded ring buffer of the
  last 64 user/lifecycle events. 5 unit tests cover ordering, capacity,
  timestamps, snapshot defensive-copy, mostRecent.
- `hivens.launcher.diag.DiagnosticBundle` — ZIP packager for support
  bundles. Routes log file contents through `Redactor` a second time as
  defence-in-depth.
- `hivens.launcher.diag.IssueReporter` — pure URL builder for the
  GitHub-Issue "report" buttons. Body capped at 6000 chars raw (URL-
  encoded grows ~3x for non-ASCII), keeping total URL under any
  reasonable browser cap. Stack traces truncated to 3000 chars;
  ActionRing snapshot truncated to last 20 entries; everything routed
  through `Redactor` so accessTokens / UUIDs in stack-trace URL params
  never reach the github.com tab. 9 unit tests cover URL prefix, query
  param presence, body content inclusion, redaction, length cap,
  determinism.
- `Branding.REPO_SLUG` / `REPO_URL` / `ISSUE_NEW_URL` constants —
  centralised so a fork doesn't grep for hard-coded URLs across the UI.
- "Create diagnostic bundle" + "Report on GitHub with bundle" buttons
  under Settings → Diagnostics, plus i18n keys (EN / RU / DE):
  `settingsCreateDiagnosticBundle`, `settingsDiagnosticBundleHint`,
  `settingsReportOnGithub`. The Report-on-GitHub button is disabled
  until a bundle exists in this session, then copies the ZIP path to
  the clipboard and opens the pre-filled Issue editor.
- "Report on GitHub" added as the first option on the crash dialog
  (next to Copy report / Open folder / Close).
- Regex toggle (`.*`) in the in-app `ConsoleWindow` search bar. Tinted
  green when the pattern parses, red while invalid, grey when off.
  Failed compile collapses to "match nothing" rather than crashing.
- `IJavaManager` interface in `client-core/api/interfaces/` — abstracts
  the managed-Java runtime contract so tests can substitute a fake
  without configuring the mockk inline-mock-agent.
- `LaunchPipelineIntegrationTest`: 5 cases driving auth via MockEngine
  through the real `ManifestProcessorService` → `ClasspathProvider` →
  `GameCommandBuilder` chain on a tmpdir client root. Catches
  orchestration regressions (auth shape changes, manifest format drift,
  version → mainClass mapping, profile-vs-allocated memory interaction)
  the per-component unit tests miss.
- 5 new `LauncherServiceTest` cases covering the `resolveJavaPath`
  priority cascade with a fake `IJavaManager`.
- Login-required placeholder + i18n keys
  (`dashboardLoginRequiredTitle` / `dashboardLoginRequiredHint`) for
  EN / RU / DE.

### Changed
- `LauncherService.resolveJavaPath` lifted into the internal companion
  (was an instance method) taking `IJavaManager` as parameter — same
  pattern as the existing `normalizeMemory`. Production behaviour
  unchanged.
- `LauncherController` now injects `IJavaManager` (interface) instead
  of `JavaManagerService` (concrete) — matches the DI binding registered
  in `Modules.kt`. Without this fix, the controller would have failed
  with `NoBeanDefFoundException` on the first Play click after the
  IJavaManager extraction.
- `ProcessLogHandler` writes game stdout/stderr through the
  `hivens.launcher.game` SLF4J channel (routed to `game.log` by Pulse).
  Previously called `println` / `System.err.println` which polluted the
  launcher's own stdout without persisting anywhere durable.
- `CrashReporter.lastAction` (one global mutable string) replaced by
  `ActionRing.snapshot()`. Crash reports now print the action history
  trail instead of a single line.
- Multiple `catch (_: Exception) {}` silent failures across UI code
  (news fetch, link Desktop.browse, tray-launched login, cached-
  credential auto-login, JSON decode fallback, per-session log file
  open, console mirror write) replaced with explicit `log.warn(...)` /
  `log.debug(...)`. Means the new log files actually carry signal.
- `AutoSyncService` records start (with installed server list),
  skip-no-creds, skip-no-installed, and complete (succeeded/failed/skipped
  counts) into `ActionRing` so the diagnostic bundle reflects what the
  launcher was doing.
- Login attempt + result, SSL bypass acceptance also recorded into
  `ActionRing` for the same reason.

### Fixed
- KDE/GNOME tray hover text was permanently "SystemTray" because
  dorkbox's `SystemTray.get()` no-arg overload defaults the
  AppIndicator title (set via `app_indicator_set_title()`) to the
  literal `"SystemTray"`. The previous attempt at fixing this
  (dropping `setTooltip()` in 2.2.10) was based on the wrong assumption
  that KDE would fall back to the `.desktop` `StartupWMClass` —
  AppIndicator does not. Real fix: pass `Branding.TITLE` to
  `SystemTray.get(name)`.
- Resource leak in `JavaManagerService.downloadAndUnpack`:
  `FileOutputStream(archive.toFile())` was never closed, so on Windows
  the subsequent `Files.deleteIfExists(archive)` in the `finally`
  block silently failed (returned `false`) and the JDK installer
  payload accumulated in `%TEMP%` between Java-runtime downloads.
  Fixed with `.use { }`.
- Resource leak in `ProcessLogHandler.pipeOutput`:
  `BufferedReader(InputStreamReader(stream))` stayed referenced until
  the GC reclaimed the daemon thread. Fixed with `.use { }` plus a
  switch to `lineSequence().forEach` for the modern idiom.
- 19 unused i18n keys removed across `AppStrings` + the three locale
  implementations: `appVersion`, `loginSuccess`, `loginLoading`,
  `navHome`, `navProfile`, `navSettings`, `navConsole`, `dashboardNews`,
  `settingsSeasonEffect`, `settingsSeasonEffectSub`, `newsLoading`,
  `newsNoImage`, `serverDetailLoading`, `serverDetailMissingBody`,
  `trayShowHide`, `fileCheckIntegrity`, `fileNoUpdates`,
  `fileClientSetup`, `aboutJvmHeap`. Compile-verified zero references
  in non-i18n source; cleanup only, no UI change.

## [2.2.10] - 2026-05-12

UX polish chunk anchored on the new visual JVM Args Builder — a Compose
dialog for picking GC algorithm and tuning flags so users no longer
have to hand-type Aikar's recipe to get smooth modded MC. Plus the
usual round of stability fixes, a saner default heap size, full
Console-window localisation that was previously hardcoded English,
and a new gothic dark-red theme.

### Highlights
- **Visual JVM Args Builder** (experimental opt-in): pick GC (G1 / ZGC
  / Shenandoah / ParallelGC / SerialGC), tune G1 region size and pause
  targets via sliders, enable AppCDS or JFR profiling — all without
  memorising `-XX:+UnlockExperimentalVMOptions`. Six curated presets
  cover Aikar's flags (canonical modded MC), Heavy modded (GTNH-class),
  Vanilla G1 (stock baseline), ZGC and Shenandoah for huge heaps, plus
  ParallelGC throughput. Live preview at the bottom shows the composed
  arg string. Enable under Settings → Experimental features.
- **Auto-sync installed packs on launch** (experimental opt-in): the
  launcher quietly refreshes every server pack you've already installed
  at startup. Useful if you hop between multiple servers and want
  fresh state without clicking each one. Sequential to avoid bandwidth
  contention. Cheap when nothing changed — the 2.2.9 manifest cache
  short-circuits the integrity walk.
- **NeoForge `--fml.*` args auto-detect**: launcher now reads the
  required NeoForge / FML / NeoForm version values directly from the
  populated `libraries-{mc}/` directory and the universal jar's
  manifest. Removes the recurring "smrt-deco bumped, Aura's hardcoded
  version doesn't match, NeoForge fails to register the `neoforge`
  mod and every dependent mod shows `[MISSING]`" failure mode. Baked-
  in values stay as a safety-net fallback.
- **Default heap bumped 4 → 6 GB** for new per-server profiles: 4 GB
  was borderline tight for the SmartyCraft modpack class (50-70 mods).
  RamSelector still caps choices at 75 % of detected system RAM, so
  the default scales down gracefully on low-RAM machines.
- **Blood Rain theme**: first warm-dark gothic option in the theme
  picker. All accents stay inside the dark-red family (no cool
  counterpoint) for a "blood rain on a moonless night" mood. Sits
  opposite the existing cool-electric presets (Cyberpunk / Vaporwave
  / Synthwave / Neon Dreams).
- **Console window fully localised** (EN / RU / DE): window title,
  filter labels, action tooltips, search placeholder, jump-to-bottom
  button — all previously hardcoded English. Three i18n keys
  (`consoleTitle`, `consoleCopyAll`, `consoleClear`) had existed in
  `AppStrings` since an earlier refactor but were never wired to the
  screen; fixed alongside the new keys.
- **RAM custom-value field no longer clips its placeholder**: the
  `OutlinedTextField` had been forced to 48 dp height, below the
  Material3 default ~56 dp the placeholder layout assumes. The
  placeholder digit appeared to "fall through" the bottom border.
- **Server settings bottom buttons unified**: Open Folder, Reset
  Client, and Return to Spawn now all render in the same outlined
  Celestia style. Open Folder and Spawn Reset were previously
  `AprilFoolsButton` with a transparent-container hack that made them
  read as floating text instead of buttons.
- **Tray init race fix**: the close-request callback treats a close
  as "minimise" while the tray subsystem is still initialising.
  Previously, on systems where dorkbox/SystemTray takes up to a
  minute to fall back to the GTK status icon, the launcher could
  exit before the tray ever appeared — silently, with no error.
- **Offline launches now rebuild the classpath**: per-server
  `ManifestCache` persists the full manifest content alongside its
  hash, so `LauncherController`'s offline branch has the data it
  needs. Previously the cache stored only the hash and offline mode
  produced an empty classpath that failed with a confusing
  class-not-found error.

### Added
- `JvmConfig` model in `client-core/jvm/` composing `G1Tuning` /
  `ZgcTuning` / `ShenandoahTuning` / `CdsConfig` / `JitConfig` /
  `PerfFlags` / `JfrConfig` into a single `toArgs()` argument list.
  Pure data, serialisable. 27 unit tests cover GC dispatch, exact-
  match against the canonical Aikar's flags recipe (catches accidental
  drift), CDS / JFR edge cases, composition order, custom passthrough.
- `JvmArgsPresets` catalog: six well-documented `JvmPreset` entries
  (Aikar / HeavyModded / VanillaG1 / ZgcLowLatency /
  ShenandoahLowLatency / Throughput) with `minRecommendedHeapMb` and
  `minJavaVersion` metadata so the UI can warn when an environment
  doesn't satisfy a preset.
- `JvmArgsBuilderDialog` Compose modal (820 × 540 dp): preset chips,
  seven categorised tabs (GC / per-GC tuning / AppCDS / JIT /
  Performance / JFR / Custom passthrough), live preview pane.
  Reachable from the per-server constructor when
  `jvmBuilderEnabled` is on. Full EN / RU / DE for descriptive copy;
  technical `-XX:` flag identifiers stay literal.
- `SettingsData.jvmBuilderEnabled` experimental toggle.
- `AutoSyncService` walks every installed pack on startup and re-runs
  the manifest sync. Skips servers with no client directory (never
  triggers a fresh many-GB pack download without an explicit launch)
  and servers where the user has no cached credentials.
- `SettingsData.autoSyncAllPacks` experimental toggle, plus a
  `Dashboard` progress strip and per-card sync badges so the user can
  see what's running.
- `NeoForgeVersionDetector` component reading
  `libraries-{mc}/net/neoforged/{neoforge,fancymodloader/loader}/`
  directory names and parsing `Implementation-Version` out of the
  universal jar's `MANIFEST.MF` to recover all four `--fml.*` args.
  6 unit tests cover happy path, multi-version selection, missing
  directories, and manifest-without-neoform-section fallback.
- `BLOOD_RAIN` theme preset in `ThemePresets`.
- Console window i18n keys: `consoleHeaderCount(filtered, total)`
  (replaces the single-int `consoleTitleCount(n)` whose signature
  never matched the use site), `consoleWrap`, `consoleSaveToFile`,
  `consoleSearchPlaceholder`, `consoleJumpToBottom`. EN / RU / DE.

### Changed
- `SettingsData.memoryMB` and `InstanceProfile.memoryMb` defaults
  bumped 4096 → 6144.
- `GameCommandBuilder` reads `--fml.*` arg values from
  `NeoForgeVersionDetector` first, with the previous baked-in values
  preserved as a fallback. Logs `WARN` when fallback fires so version
  drift surfaces in the launcher log instead of as an unexplained
  in-game `[MISSING]`.
- Three bottom action buttons on `ServerSettingsScreen` (Open Folder,
  Reset Client, Return to Spawn) unified to `CelestiaButton(primary =
  false)`. Open Folder and Spawn Reset lose their April Fools chaos
  integration as a result — the deferred fix needs a proper
  CelestiaButton-based wrapper for `AprilFoolsButton` that respects
  the design language, queued under future Atelier-phase work.
- Dependency versions: ktor 3.4.1 → 3.4.3, koin 4.2.0-RC2 → 4.2.1
  (RC → stable), kotlinx-serialization 1.10.0 → 1.11.0,
  kotlinx-coroutines 1.10.2 → 1.11.0, multiplatform-markdown-renderer
  0.39.2 → 0.40.2, filekit 0.13.0 → 0.14.1, versions plugin
  0.53.0 → 0.54.0. Held back: Kotlin (next is 2.4.0-Beta2, beta of
  a new major), Coil (3.5.0-beta01), Compose (1.11.0-rc01 not yet
  on Maven Central).
- `--fml.neoForgeVersion` baked-in fallback bumped 21.1.505 → 21.1.506
  to match smrt-deco 3.6.5 (since superseded by the auto-detect path
  but kept current as the safety net).

### Fixed
- Tray initialisation race: the close-request handler treats a close
  as minimise while `TrayManager.canBeReady` is true (state ∈
  `INITIALIZING` or `READY`), avoiding the silent-exit failure on
  systems where dorkbox's GTK probe takes up to a minute.
- Offline launches now rebuild the classpath: per-server
  `ManifestCache` persists the full manifest content alongside its
  hash, so `LauncherController`'s offline branch can pass it to
  `ClasspathProvider`.
- RAM custom-value placeholder text no longer clips through the bottom
  border of its `OutlinedTextField`.
- ConsoleWindow's eight previously-hardcoded user-facing strings are
  now read from `LocalStrings`.
- Material3 deprecated APIs replaced in `JvmArgsBuilderDialog`:
  `Divider` → `HorizontalDivider`, `ScrollableTabRow` →
  `PrimaryScrollableTabRow`.
- `System.runFinalization()` calls in `SkiaTracker` debug panel
  removed — deprecated in Java 18 and a no-op since Java 9 anyway.

## [2.2.9] - 2026-05-10

Stability sweep — four user-visible reliability fixes that ride on the
infrastructure shipped in 2.2.8. Targeted at the failure classes observed
in production logs: mid-stream HTTP/2 resets on the SOCKS-proxied
SMARTYcraft channel, downloads restarting from byte 0 on every flake,
duplicate auth requests on the dashboard → Play flow, and the
single-instance gate failing to actually raise the existing window on
KDE / Hyprland / GNOME.

### Highlights
- **Cold-start much faster after a clean session**: when the server
  manifest hasn't changed since the last successful sync (TTL 7 days),
  the launcher skips the per-file MD5 integrity walk. On a 1000-file
  modpack this collapses multi-second checks into a single hash compare.
- **Orphan files now actually leave**: when the upstream modpack
  removes a mod, the corresponding local file is pruned on next sync
  (was: lingered forever, often causing mismatch crashes on join).
- **User-extendable protected-paths list**: drop a mod into
  `dataDir/protected-paths.json` and the launcher will never overwrite
  configs under that directory, even when the manifest says they're
  stale. Defaults shipped with the file on first run.
- **SMARTYcraft channel pinned to HTTP/1.1**: h2 multiplexing over the
  upstream SOCKS proxy was dropping mid-stream on long bodies. 1.1 with
  parallel connections trades multiplexing for resilience. Direct channel
  (GitHub releases, BellSoft JDKs, Maven Central) is unaffected.
- **Auth and downloads now retry on transient resets** (3 attempts, 1 s /
  3 s / 9 s backoff). Auth-rejection responses and SSL cert errors are
  explicitly *not* retried — those need user attention, not a silent loop.
- **Downloads resume via `Range:`** instead of restarting from byte 0.
  A 100 MB asset that drops at 70 % now costs seconds to recover instead
  of restarting the whole transfer.
- **Per-server session cache** in `AuthService`: dashboard list refresh
  and the actual server-launch auth used to fire two back-to-back logins
  for the same server. The second one now returns the 30-second-cached
  session without hitting the network — fewer requests, fewer chances to
  trip the upstream's "sessions don't dedup" race.
- **Single-instance gate raises the existing window**: second-launch
  attempts previously only flipped `visible = true`, leaving the window
  minimised or buried under other windows on KDE / Hyprland / GNOME.
  Now un-minimises and pulses `isAlwaysOnTop` to force a true raise.
  Lock file also stores the holder PID for diagnostics
  (`cat ~/.local/share/aura-launcher/.lock`).

### Added
- `RetryWithBackoff` utility in `client-core/util/`. Generic suspend
  wrapper with caller-supplied retry predicate; deliberately narrow.
  5 unit tests cover the predicate contract.
- `Network.FORCE_HTTP1_FOR_SMARTYCRAFT` knob (default true). Wired via
  an `OkHttpClient.Builder` extension in `Modules.kt` so secure and
  insecure smartycraft clients pick it up identically.
- `SingleInstance` helper in `client-launcher/.../platform/`. Holds the
  channel + lock on a static field, registers a shutdown hook with audit
  log line, writes the holder PID into the lock file. 4 unit tests.
- `.show` watcher in `Main.kt` raises the window via `windowState.isMinimized = false`,
  `toFront()`, and the `isAlwaysOnTop` pulse trick (the only cross-WM way
  to force a focus-steal-like raise on X11/Wayland).
- Per-server session cache in `AuthService` with 30 s TTL. Dashboard load
  and Play within the same server are deduplicated to one network request.

### Changed
- `AuthService.login` and `FileDownloadService.downloadFileInternal`
  wrapped in `retryWithBackoff` with predicates that walk the full
  cause chain looking for `ConnectException` / `SocketException` /
  `ClosedByteChannelException` / `SocketTimeoutException` and "Connection
  reset" `IOException`s. `CancellationException` is explicitly excluded.
- `FileDownloadService` sends `Range: bytes=N-` when a partial file is on
  disk; handles 206 (append), 200 (server ignored Range, overwrite),
  416 (clear bad partial and refetch) explicitly.
- `DataDirMigration.run` defers to a new `Path.hasUserData()` probe that
  ignores housekeeping files (`.lock`, `.show`, `.migrated`) — a
  follow-up to the lock-before-migration order so first-run still triggers
  when the lock file already exists in the target dir.
- `UpdateApplicator` (320-line `object`) split into `IUpdateApplicator`
  interface + `Windows`/`Mac`/`Linux`/`NoOp` implementations selected by
  `OS` at startup. Per-platform shutdown hooks register exactly once via
  Koin singleton. `UpdateDialog` switched from static call to injected
  interface.
- `extra.zip` unpacking now snapshots the extracted file list into
  `.extra_unpacked_index.json`. On the next sync, files in the old
  snapshot but not in the new one are pruned (orphans removed by the
  upstream modpack). Protected paths are never touched even if they
  appeared in the previous index.
- `FileDownloadService.processSession` short-circuits the per-file MD5
  walk when the manifest hash matches the last successful sync (TTL
  7 days). Cache lives at `dataDir/manifest-cache/<server>.json`. On
  a 1000-file modpack this turns multi-second cold-start integrity
  checks into a single hash comparison.

## [2.2.8] - 2026-05-10

Update Channels chunk — gives the launcher two new tools for surviving the
upstream cadence: a server-controlled mandatory-update floor (so the launcher
refuses to start when the protocol breaks compat with installed builds), and
an opt-in pre-release channel (so RC builds reach users before the next
stable cut). Both gated by a master "Experimental features" toggle. Shipped
as a non-prerelease so existing 2.2.7-rc3 users actually receive it — older
launchers ignore prereleases by GitHub API contract.

### Highlights
- **Mandatory updates**: launcher refuses to start when the installed version
  drops below `mandatory_min_version` published in `meta/update-channel.json`.
  No new server infra — the file lives on the `stable` branch and is updated
  via PR. Triggers a non-dismissable dialog with "Install" or "Quit".
- **Pre-release update channel**: opt in to receive RC and beta builds before
  the next stable. Currently ON by default while the upstream protocol is a
  moving target; expected to flip to OFF once cadence stabilises.
- **Experimental features master toggle** in Settings — gates both knobs
  above with a single switch for users who want a calm upgrade story.
- **Near-real-time mandatory rollouts**: a long-running launcher session
  polls `update-channel.json` every 5 minutes (cheap, no GitHub API quota),
  so when an emergency upgrade is published the user sees the blocking
  dialog within ~5 minutes — no need to restart the launcher to pick it up.
  Routine release checks stay on the existing 12 h cadence.
- Strict version comparison in the update flow: `1.3.0 > 1.3.0-rc3`,
  `rc1 < rc2 < rc3`, `alpha < beta < rc`. Without this the prerelease channel
  would consider RC bumps within the same base "the same version".

### Added
- `meta/update-channel.json` — out-of-band channel metadata fetched via the
  direct HTTP channel (no SMARTYcraft proxy dependency). Carries
  `mandatory_min_version` and an optional human `reason` shown in the
  blocking dialog. Initial value is `null` (no floor); flipping it activates
  enforcement on the next update check (within the 12 h cooldown).
- `UpdateChannelMeta` data class wrapping the JSON above.
- `LauncherUpdate.isMandatory` / `mandatoryReason` fields propagating the
  decision to the UI layer.
- Three booleans on `SettingsData`: `experimentalFeaturesEnabled` (master),
  `mandatoryUpdatesEnabled`, `prereleaseChannelEnabled`. All default to ON.
- "Experimental features" section in `SettingsScreen` with Material icons,
  master + two children, sub-rows greyed out when the master is off.
- `UpdateDialog` mandatory mode: red banner with reason, no "Later" button,
  hard "Quit" button (clean `exitProcess(0)`), backdrop dismiss disabled.
- `UpdateService` tests: 6 new cases for channel selection (prerelease ON
  vs OFF, master OFF forces both children OFF) and mandatory floor (above
  current, at-or-below, missing meta, mandatory toggle OFF, v-prefix
  normalisation), plus 4 new SemVer-suffix cases for `compareVersions`.

### Changed
- `UpdateService.compareVersions` is now strict on prerelease suffixes
  (was: strip suffix and compare numeric base only). Final beats any RC at
  the same base; lex compare on the suffix orders `alpha < beta < rc1 < rc2`
  for the launcher's release cadence.
- `UpdateService` reads `ISettingsService` and dispatches between
  `/releases/latest` (stable channel) and `/releases?per_page=20` filtered
  for non-draft entries (prerelease channel).
- `UpdateManager` routes mandatory updates straight to the modal dialog
  (skipping the corner notification), same as critical updates.

## [2.2.7-rc3] - 2026-05-10

Release candidate for [2.2.7], superseding rc2 with the freshly-rotated
upstream version pin (smrt-deco 3.6.5, pushed 2026-05-10) and a runtime
knob to ride out the *next* upstream rotation without waiting for a
launcher release. CI internals also got a couple of paper-cut fixes —
metainfo injection now uses `xmlstarlet` instead of regex-on-XML, and
the AppImage assembly bash moved from inline yaml into a shell script.

### Highlights
- Mimicked launcher version bumped to **SMARTYcraft 3.6.5** (rc2 was 3.6.4).
  No protocol bytes changed beyond the version string; proxy creds, AES
  params and salt are all unchanged.
- New **experimental override** for the mimicked version: pass
  `-Dsmrt.mimic.version=X.Y.Z` on the JVM command line to claim a different
  launcher version without rebuilding. Useful when upstream rotates the
  pin and a launcher update has not shipped yet.

## [2.2.7-rc2] - 2026-05-07

Release candidate for [2.2.7]. Same code; canary tag for catching install
regressions on Windows / macOS / Linux before the public bump. (rc1 failed
on Inno Setup `VersionInfoVersion` strict-version validation; fixed by
stripping the pre-release suffix in setup.iss the same way build.gradle.kts
already does for Compose's `packageVersion`.)

### Highlights
- **Required upgrade** once promoted: SMARTYcraft 3.6.4 protocol sync, plus a
  new direct HTTP channel that keeps auto-update alive when the upstream
  proxy is unreachable. See [2.2.7] below for the full notes.

## [2.2.7] - 2026-05-07

### Highlights
- **Required upgrade**: SMARTYcraft 3.6.5 protocol sync — proxy credentials
  rotated upstream, so anything older than this build cannot authenticate.
- Auto-updater and JDK/natives downloads now bypass the SMARTYcraft proxy,
  so the launcher can still update itself when the upstream is unreachable.
- Window icon and WM_CLASS render correctly on KDE Plasma, Hyprland and
  GNOME — workspace overviews show the proper hi-res launcher icon instead
  of a generic "broken file" glyph, on every JDK vendor.
- Per-OS data directory with automatic migration from `~/.aura`; relocate
  via the `AURA_DATA_DIR` env var.
- Update dialog reads a tidy "What's new" summary from a published
  `release-manifest.json` instead of scraping the raw changelog body.

### Added
- Direct HTTP channel (`HttpClientProvider` qualified `named("direct")`) for
  third-party CDNs that don't tunnel through the SMARTYcraft proxy. Used
  by `UpdateService`, `JavaManagerService` and `EnvironmentPreparer` so
  GitHub releases, BellSoft JDKs and Maven Central LWJGL natives stay
  reachable across SMARTYcraft outages.
- `Branding.WM_CLASS` constant — single source of truth for the X11/Wayland
  app identity that must match `StartupWMClass=` in the .desktop entry and
  the AppStream metainfo `<id>` slug.
- Per-OS data directory: `%APPDATA%\AuraLauncher` on Windows,
  `~/Library/Application Support/AuraLauncher` on macOS,
  `~/.local/share/aura-launcher` on Linux. Override via `AURA_DATA_DIR`.
- `release-manifest.json` published alongside binaries; in-app dialog
  renders a Highlights-only "What's new" view instead of the raw body.
- Documentation site (Astro Starlight) deployed to GitHub Pages with
  Russian localization; CONTRIBUTING, SECURITY and issue templates.

### Changed
- **Protocol sync (smrt-deco 3.6.3 → 3.6.5)**: `MIMIC_LAUNCHER_VERSION`
  3.6.3 → 3.6.5; SOCKS proxy port 1080 → 58613, user
  `proxyuser` → `smartycraftproxyuser`, password rotated.
  `MIMIC_LAUNCHER_VERSION` is now runtime-resolvable via
  `-Dsmrt.mimic.version=X.Y.Z` (gated behind a new
  `@ExperimentalProtocolOverride` opt-in marker) so users can react to
  the next upstream rotation without waiting for a launcher build.
- `AppConfig` split into `Branding` / `Network` / `Protocol` / `Storage`
  for clearer ownership; `LauncherService` collaborators are now
  constructor-injected (DI-friendly, mockable).
- Single-source icon pipeline: edit `resources/branding/app-icon.png` and
  `tray-icon.png`, run `scripts/regenerate-icons.sh` to produce every
  derived variant. Multi-size Windows ICO (16/32/48/64/128/256) replaces
  the single 64-px frame Explorer used to downscale to blurry placeholders.
- Dependency versions consolidated into `gradle/libs.versions.toml`.

### Fixed
- **WM_CLASS mismatch on Linux**: KDE / Hyprland / GNOME workspace overviews
  now match the live window to `aura-launcher.desktop` and pick up the
  hicolor icon. The previous fix relied on `-Dawt.appClassName`, which only
  JBR honours; the launcher now reflects into
  `sun.awt.X11.XToolkit.awtAppClassName` so stock OpenJDK distributions
  (Liberica, Temurin, …) work too.
- **Auto-updater survives SMARTYcraft proxy outages**: GitHub release fetch
  and binary download now route through the direct HTTP channel and no
  longer require the upstream SOCKS proxy to be reachable.
- **`Res.drawable.icon` startup crash**: moved `icon.ico` out of
  `composeResources/drawable/` to `resources/icons/` so Compose Resources
  no longer indexes two files under the same stem and `painterResource`
  resolves cleanly.

### Removed
- Compose `linux { iconFile.set(...) }` block — the Linux package
  distributable task is not invoked; releases ship via AppImage assembled
  in CI from `resources/icons/`.

## [2.2.6] - 2026-03-26

### Fixed
- **Launcher collapses repeatedly when reopened during active game session**:
  added per-session flag so the launcher hides only once after game starts,
  not on every subsequent open while the game is running.
- **Single instance support**: launching a second instance now brings the
  existing window to foreground instead of opening a duplicate process.

### Changed
- **Update dialog**: increased width and changelog area height for better
  readability.
- **Idle status label**: renamed "Готов к игре" to "Ожидание" / "Idle" /
  "Wartend" across all locales for clarity.

## [2.2.5] - 2026-03-25

### Fixed
- **SSL bypass now actually works globally**: replaced direct `HttpClient`
  injection in all repositories with `HttpClientProvider` — a thin wrapper
  that returns the secure or insecure client on every request based on
  `NetworkState.sslBypassEnabled`. Previously Koin singleton creation order
  caused repositories to permanently hold a reference to the secure client
  even after the user accepted SSL bypass.

## [2.2.4] - 2026-03-25

### Fixed
- **SSL bypass now applies globally**: after user confirms "Connect anyway",
  all subsequent network requests (dashboard, file sync, skins) also use the
  insecure client. Previously only login bypassed SSL verification.
- **Launcher crash on Linux (GTK detection failure)**: `TrayManager.init`
  now catches `Throwable` instead of `Exception`, preventing
  `ExceptionInInitializerError` from dorkbox/SystemTray GtkLoader from
  crashing the launcher before the window appears. Tray gracefully
  falls back to unavailable state.

## [2.2.3] - 2026-03-25

### Fixed
- **SSL certificate expired on smartycraft.ru**: raw `CertPathValidatorException` stack
  trace replaced with a human-readable orange warning banner. Users can now make an
  informed decision — "Connect anyway" retries the login with SSL verification disabled,
  while "Cancel" keeps them on the login screen. The bypass is entirely opt-in.

## [2.2.2] - 2026-03-25

### Fixed
- **Launcher crash on Windows — JNA native library version conflict**: JetBrains Runtime 25
  unpacks its own JNA 7.0.2 native library into `%TEMP%` on startup. dorkbox/SystemTray 4.4
  performs a hardcoded version check and throws `java.lang.Error` upon finding 7.0.2 instead
  of the expected 6.1.6, crashing the launcher before the window appears.
  Resolved by explicitly pinning JNA to 6.1.6 across all dependencies via Gradle resolution
  strategy, overriding the version bundled by JBR.

## [2.2.1] - 2026-03-25

### Fixed
- **Launcher crash on Windows with JBR 25**: JetBrains Runtime 25 bundles JNA 7.0.2
  natively and unpacks it to `%TEMP%` on startup. dorkbox/SystemTray 4.4 performs
  a version check and throws `java.lang.Error` when it finds 7.0.2 instead of the
  expected 6.1.6, crashing the launcher before the window appears.
  Downgraded dorkbox/SystemTray from 4.4 to 4.3 as a workaround.
  Root cause is dorkbox abandonment in 2023 — no JNA 7 support will be added upstream.

### Known Issues
- dorkbox/SystemTray is unmaintained since 2023. Full replacement planned if JNA
  compatibility issues resurface on future JBR versions.

## [2.2.0] - 2026-03-25

### Changed
- Minor UI improvements and stability fixes
- Internal refactoring

## [2.1.0] - 2026-03-18

### Changed
- **`SkinManager`** refactored from `object` to `class`; now receives `HttpClient`
  via Koin injection instead of using `HttpURLConnection` directly. Proxy, timeouts
  and User-Agent from the global network configuration now apply to skin downloads.
- `AboutScreen`: removed local `OkHttp` `ImageLoader` instance; uses the global
  singleton set up in `AppRoot` instead.

### Removed
- `.github/workflows/verify_release.yml`: was failing on every release due to a
  race condition between the `workflow_run` trigger and asset upload completion.
  Asset naming is already guaranteed by `build_release.yml`; `scripts/verify-release.sh`
  is kept for manual use.
- Animated GIF/WebP background support dropped permanently.
  All attempts (native Skiko frame scheduler, Kamel, Coil 3) produced either
  native memory leaks or severe UI stutter due to Compose Multiplatform desktop
  architecture constraints. Static images are unaffected.

### Notes
- OkHttp → CIO engine migration is blocked by
  [KTOR-5961](https://youtrack.jetbrains.com/issue/KTOR-5961) —
  SOCKS5 authentication is not implemented in the CIO engine.
  Will revisit when the proxy requirement is lifted.

## [2.0.8] - 2026-03-18
### Added
- **Abyssal** theme to `ThemePresets` — deep ocean palette with cold blue accents.

### Fixed
- **SkinManager cache invalidation bug**: `getSkinBack` was using `encodeNickname()` for
  the disk cache filename (`back_Player%20Name.png`) while `invalidate()` deleted by raw
  nickname (`back_Player Name.png`), leaving stale cache files on disk permanently.
  File paths now always use raw nickname; URL encoding applies only to network requests.
- **SkinManager native memory leak**: `saveBitmapToDisk` was not closing the temporary
  Skia `Image` after `encodeToData()`, leaking native memory on every cache write.
- **URL encoding in file downloads**: `FileDownloadService` was only replacing spaces
  with `%20`; all path segments are now properly percent-encoded via `URLEncoder`.
- **`smarty_hash.cache` relative path**: `ServerRepository` now stores the hash cache
  inside `dataDirectory` instead of a process-relative path.
- **`TrayManager.setGameStatus`**: "Running" and "Ready" labels were hardcoded in English;
  now use the localized strings passed to `init()`.
- **`ProfileScreen` file picker**: replaced AWT `FileDialog` with `FileKit` for
  consistency with the rest of the codebase.

### Changed
- `getMD5` extracted from `AuthService`, `PlayerRepository`, `SkinRepository` into
  shared `HashUtils.md5()` in `client-core`.
- `CustomTheme` migrated to `@Serializable`; manual `toJson()`/`fromJson()` removed.
- `toCelestiaColors()` removed from `CustomTheme` — was never called; `background` and
  `surface` fields are kept for `ThemePickerScreen` preview only.
- `SimpleDateFormat` replaced with `DateTimeFormatter` in `ServerListService`.

### Removed
- `NewsScreen.kt` — was `@Deprecated`; UI fully covered by `CompactNewsFeed`.
- `IManifestProcessorService.processManifest()` and its stub implementation — zero call sites.
- `SettingsData.defaults()` — identical to the default constructor `SettingsData()`.
- `@Throws` annotations on `IAuthService` and `IFileDownloadService` — no effect in
  Kotlin-only codebase, meaningless on `suspend fun`.
- Redundant `|| config.assetIndex == "1.21.1"` conditions in `GameCommandBuilder` —
  `1.21.1` config always uses `BootstrapLauncher`, making the extra check unreachable.
- Dead `skiaImageToBitmap()` from `SkinManager`.

## [2.0.7] - 2026-03-17

### Changed
- **Update dialog changelog**: instead of showing raw release body (including
  Downloads table and SHA256 checksums), now aggregates "What's Changed"
  sections across all versions between the installed and latest release.

## [2.0.6] - 2026-03-17

### Fixed
- **Launcher crash on Windows when JNA version conflict is present**: dorkbox
  SystemTray throws `java.lang.Error` (not `Exception`) when it detects an
  incompatible JNA native library cached in `%TEMP%`. Added `jna.nosys=true`
  system property in `main()` and jvmArgs so JNA always uses its bundled native.
  Widened `TrayManager.init` catch to `Throwable` as a second line of defence.
  With `startInTray=true` on affected Windows machines the launcher would silently
  die before the window ever appeared — now it gracefully falls back to showing
  the window when tray init fails.

## [2.0.5] - 2026-03-17

### Added
- **Register button in login panel** (#105): added "Create an account" `OutlinedButton`
  below the login button; opens `BASE_URL/register` in the system browser so users
  can immediately tell which account type they need.
- `loginRegister` i18n key added to `AppStrings`, `RussianStrings`, `EnglishStrings`,
  `GermanStrings`.

## [2.0.4] - 2026-03-15

### Changed
- **Animated GIF/WebP background disabled**: animated paths in `CustomBackground`
  removed until 2.1.0 (Kamel rewrite). GIF/WebP files now show the first frame
  as a static image. This eliminates the primary source of native Skia memory
  accumulation (`BG.gif.frame` objects growing to 200+ between GC cycles,
  causing RSS to reach 6–8 GB). Static image backgrounds are unaffected.

### Fixed
- **`CustomBackground` GC hint on settings change**: `System.gc()` called when
  background is disabled or image path changes, prompting JVM to collect
  orphaned `SkiaBackedImageBitmap` finalizers sooner. Previously RSS would
  hold at 8+ GB until JVM decided to GC on its own.

### Added
- `SkiaDebugOverlay` — internal debug composable showing live RSS, JVM heap,
  and tracked Skia object counts with Force GC buttons. Used to diagnose
  and confirm memory fixes. Not included in release builds.
- `SkiaTracker` — lightweight weak-reference tracker for `ImageBitmap` objects.

### Known Issues
- Animated GIF/WebP backgrounds show first frame only — full animation
  support returns in 2.1.0 via Kamel
- Static background `BG.static` count may reach 3–4 between GC cycles
  when rapidly switching images; clears on next GC cycle
## [2.0.3] - 2026-03-15

### Known Issue - Custom Background
> **It is highly recommended not to use the Custom Background feature in this version.**
> Despite the partial fix for the native memory leak, enabling/disabling the background
> does not release resources completely, but animated GIFs/WebPs continue to accumulate
> native Skia objects. The function will be redesigned in the next release.
> Use at your own risk.

### Fixed
- **Partial native memory leak fix in `CustomBackground`**: replaced `toImageBitmapSafe()`
  (shallow `Bitmap.asComposeImageBitmap()` wrapper) with `Image.makeFromBitmap` +
  `toComposeImageBitmap()` + `img.close()` in both static and animated paths.
  `toImageBitmapSafe()` extension removed entirely. `ManagedSCleanerThunk` count reduced
  from 645 to 468 in VisualVM heap dump.
- **Partial native memory leak fix in `SkinManager`**: `Canvas` in `skiaImageToBitmap`
  now closed via `try/finally`; all `Image.makeFromEncoded` call sites in
  `getSkinFront`/`getSkinBack` close the Skia `Image` immediately after
  `toComposeImageBitmap()`; `saveBitmapToDisk` closes intermediate `Image` after encode.

### Known Remaining Issues
- Toggle off/on of custom background does not fully release native Skia resources
- Animated GIF/WebP frames continue to accumulate `SkiaBackedImageBitmap` instances
  until GC decides to collect them — JVM does not account for native memory pressure
- Root cause: `Image.toComposeImageBitmap()` on Compose Multiplatform desktop wraps
  rather than deep-copies pixel data; closing the source `Image` may invalidate the
  returned `ImageBitmap`. Full fix requires rewrite using a proper image-loading
  library with desktop GIF support (Coil 3 does not support animated GIF on desktop).

## [2.0.2] - 2026-03-14

### Fixed
- Closed Skia Canvas after each frame to prevent native memory leak

## [2.0.1] - 2026-03-14

### Fixed
- About screen crash on packaged builds (`java.management` missing from bundled JRE)

## [2.0.0] - 2026-03-14

### Added
- **Markdown rendering in update dialog**: integrated `multiplatform-markdown-renderer-m3` (v0.39.2) 
  to properly render GitHub release notes with headers, bold, lists and code blocks instead 
  of displaying raw markdown syntax
- `CompactNewsFeed`: shimmer skeleton loader replaces spinner while news is being fetched;
  each news card is now clickable and opens `BASE_URL/news/{id}` in the system browser;
  subtle `›` arrow hint marks items as interactive
- Unit tests for `AuthService`: covers all `AuthStatus` variants, plain-text server errors,
  malformed JSON responses, HTTP 500, AES token decryption fallback, UUID sanitization,
  and `serverId` propagation
- Unit tests for `ServerRepository`: covers normal dashboard flow, empty server list,
  `UPDATE` cycle with JAR re-fetch, JAR download failure, HTTP 500, malformed JSON,
  infinite `UPDATE` loop guard, and server field mapping
- Unit tests for `UpdateService`: covers `compareVersions` (semver, prerelease, mixed
  segment counts), `findAssetForCurrentOS` (correct platform selection, Portable ZIP
  exclusion, empty asset list), `extractChecksum` (markdown table format, plain-text
  format, null body, multi-file lookup), `verifyChecksum` (correct/incorrect/empty/
  case-insensitive), `checkForUpdate` integration (newer version, up-to-date, downgrade,
  CRITICAL detection, HTTP errors, malformed JSON, missing assets, null body),
  `shouldCheck` cooldown, and `cleanupOldUpdates` file filtering
- `MockClientFactory` test fixture shared across modules via `java-test-fixtures`
- Test jobs added as a prerequisite for `changelog` / `build` steps in the release workflow
- `scripts/verify-release.sh`: pre-release smoke test that verifies GitHub release assets
  match what `UpdateService.findAssetForCurrentOS()` expects, validates SHA256 checksums,
  and checks naming conventions; supports `--draft`, tag arguments, and `DRY_RUN` mode
- `.github/workflows/verify_release.yml`: runs `verify-release.sh` automatically after
  the Release workflow completes; also runs `UpdateServiceTest` as a separate job
- Unit tests for `GameCommandBuilder`: covers all three version configs
  (1.7.10 LaunchWrapper+FML, 1.12.2 LaunchWrapper+Forge, 1.21.1
  BootstrapLauncher+NeoForge), JVM argument structure, module path
  extraction, classpath boot-module filtering, memory flags, FML args,
  custom `ignoreModulesList`, user JVM overrides, argument ordering,
  version prefix matching, and unsupported version rejection
- **Offline Mode** (#63): new toggle in Settings; when enabled, launcher skips
  authentication and file sync, launching with locally cached client files.
  Requires at least one prior online download.
- **Full Server Settings UI** (#64): JVM arguments text field, window resolution
  (width × height), fullscreen toggle, and auto-connect toggle now exposed in
  ServerSettingsScreen; all fields already existed in `InstanceProfile` but had
  no UI
- **Server Icon Upload** (#66): clickable icon slot in server settings header;
  picks a PNG/JPG and copies it to `clients/<assetDir>/icon.png` where
  `SquareServerCard` already reads it from
- **Disk Cache for Skins** (#61): `SkinManager` now caches rendered front/back
  skins and raw textures to `~/.aura/skin-cache/` with 30-minute TTL;
  eliminates redundant downloads on every screen navigation
- **AES-256-GCM Credential Encryption** (#58): `CredentialsManager` replaces
  Base64 encoding with AES-256-GCM; key derived via PBKDF2 from machine-specific
  seed; transparently migrates old Base64 credentials on first load
- **Custom Background Wallpaper**: user-selectable image/GIF as launcher backdrop
  with live-preview settings screen; supports blur (0–25 px), darkening overlay,
  opacity, saturation adjustment, parallax effect (mouse-tracking), vignette,
  color tint presets (navy / violet / emerald / bordeaux / steel), five scale
  modes (cover / contain / stretch / original / tile), and horizontal/vertical
  alignment; settings persisted in `background.json`
- **About Screen**: launcher info card (version, build date, branding), creator
  credits, technology stack list, GPLv3 license note, system info (OS, Java,
  JVM heap), GitHub / Issues / Releases links; integrated update check with
  full download-and-install flow via existing `UpdateDialog`
- `RamSelector` component: replaces imprecise slider with preset buttons
  (1–16 GB, filtered by system RAM) and manual MB input; shows system RAM
  and recommended maximum
- `ModItemCard` component: always-visible one-line description preview,
  expandable full description, category badge, JAR file list, real-time
  conflict warnings against currently enabled mods
- `ServerGrid` component: adaptive grid (`GridCells.Adaptive(200.dp)`) with
  `★ FAVORITES` / `AVAILABLE SERVERS` section headers and full-width span;
  scales from 3 columns on 1080p to 5–6 on ultrawide
- i18n: ~60 new string keys across `AppStrings`, `RussianStrings`,
  `EnglishStrings`, `GermanStrings` covering RAM selector, mod cards,
  server grid, background settings, and About screen
- Build script now exports dependency versions (`COMPOSE_VERSION`, `KTOR_VERSION`, `KOIN_VERSION`, `COIL_VERSION`)
  to `BuildConfig` for runtime usage
- **Animated Custom Backgrounds**: Hardware-accelerated GIF and WebP support for the launcher backdrop using a native Skiko decoder without third-party libraries.
- **Dashboard Empty State**: When the server list fails to load or is empty, the Dashboard now displays an explicit empty state with a manual "Retry" button.
- `com.github.ben-manes.versions` Gradle plugin to easily track dependency updates.
- **Spawn Reset**: "Return to spawn" button in server settings,
  visible only for 1.12.2 servers; sends `action=spawn` to the backend
  with HMAC signature; button cycles through Idle → Loading → Success/Error
  states with auto-reset after 3 seconds
- `PlayerRepository`: new repository for player-specific server actions
- **System Tray** (#??): replaced Compose `Tray` with `dorkbox/SystemTray` (full
  Linux SNI/AppIndicator support); tray menu includes show/hide window, open console,
  server quick-launch list, and exit; game status indicator updates to server name
  while game is running; crash detection automatically brings window to foreground
- **Start in tray** (#??): new toggle in Settings → Behavior; launcher starts
  minimized with window hidden; closing the window hides to tray instead of exiting;
  falls back to showing the window if tray is unavailable on the current platform
- `TrayManager`: new singleton object wrapping dorkbox API; init runs on a background
  thread to avoid blocking UI startup; exposes `onShowWindow`, `onExit`,
  `onShowConsole`, `onLaunchServer` callbacks and `setGameStatus()` / `updateServers()`

### Fixed
- `exitApplication` now correctly wired through `AppRoot` → `AppLayout` → `DashboardScreen`;
  "close after game starts" setting now actually closes the launcher
- Malformed import block in `ManifestProcessorService.kt` where two `import` statements
  were merged onto a single line
- Light theme no longer renders with dark background: `CelestiaTheme` now applies
  `DarkColorPalette` / `LightColorPalette` as the base and only overlays accent colors
  from `CustomTheme`, so the background, surface and text colors always match the selected
  brightness mode
- Dark/light theme preference is now persisted across restarts: `Main.kt` reads
  `SettingsData.isDarkTheme` on startup and writes it back via `ISettingsService` whenever
  the toggle is flipped
- **`UpdateService.findAssetForCurrentOS`**: was matching `.msi` on Windows, but CI
  produces `.exe` via Inno Setup — auto-update silently failed on every Windows release;
  now matches `*Setup*.exe` and explicitly excludes Portable ZIP
- **`UpdateService.cleanupOldUpdates`**: regex matched `.msi` instead of `.exe` —
  old installers were never cleaned up on Windows
- **`UpdateService.extractChecksum`**: only matched `SHA256: file - hash` plain-text
  format, but `build_release.yml` emits markdown table `| \`file\` | \`hash\` |`;
  added markdown table parser as the primary extraction path
- `ConsoleWindow.buildHighlightedText`: tail text was appended inside
  `forEach` loop — with N keyword matches the remaining text was
  duplicated N times; text with zero matches was not rendered at all;
  tail-append block moved outside the loop
- `ProfileScreen`: skin upload result was ignored — status always set
  to `Success` even on server errors (SIZE, TYPE, HD); now checks the
  `uploadSkin()` return value and displays the actual error message;
  network exceptions are also caught and shown in UI
- RAM allocation in server settings was imprecise: `Float` slider with 30 steps
  between 1–16 GB made it impossible to set exact values like 3072 or 6144 MB;
  replaced with `RamSelector` preset buttons + manual input
- Mod descriptions were hidden behind a tooltip that required hover — easy to
  miss; `ModItemCard` now always shows first line of description, with expand
  for full text
- `exitApplication` now correctly wired through `AppRoot` → `AppLayout` → `DashboardScreen`;
- **Skia Memory Leak**: Fixed severe native memory leaks in `CustomBackground` for animated
  WebP/GIF files: `SkiaImage` is now closed immediately after `toComposeImageBitmap()` on
  every frame instead of being held until the next iteration; `graphicsLayer` is only
  applied when `parallaxIntensity > 0` to avoid unnecessary offscreen GPU buffers;
  `System.gc()` hints removed (were causing GC-pause frame drops)
- **Tray Localization**: Application `Tray` and `Window` are now properly scoped inside
  `LocaleProvider` to ensure tray context menus display in the correct language.
- **Render FPS cap**: Added `skiko.fps.limit=60` system property in `main()` to prevent
  the Skiko renderer from targeting the display refresh rate (200 Hz on high-refresh
  monitors), which caused sustained ~40% GPU load on integrated graphics (Intel 1235U)
  even when the UI was idle. Launcher UI does not benefit from >60 fps.
- **`GlowModifiers` recomposition leak**: `pulsatingGlow` and `shimmerOverlay` were reading
  animated `State` values via `by` delegate at composition time, causing full recomposition
  of the Compose tree on every animation frame (~60–200×/sec). Fixed by holding the raw
  `State` object and reading `.value` exclusively inside `drawBehind` / `drawWithContent`
  draw-phase lambdas — Compose now invalidates only the draw layer, not the composition tree.
  `neonBorder` converted to a static non-animated border, eliminating its
  `rememberInfiniteTransition` entirely.

### Changed
- `UpdateDialog`: changelog section replaced plain `Text(update.changelog)` 
  with `Markdown(update.changelog)`from `multiplatform-markdown-renderer-m3`; 
  release notes now render headers, bold, bullet lists and code blocks correctly
- `client-ui`: migrated from Compose Material 2 to Material 3 (`material` → `material3`);
  `material-icons-extended` dependency retained from M2 artifact pending icon migration
- `CelestiaTheme`: `darkColors()`/`lightColors()` → `darkColorScheme()`/`lightColorScheme()`;
  `MaterialTheme(colors=…)` → `MaterialTheme(colorScheme=…)`; `primaryVariant` removed from
  M3 scheme (value folded into `primary`); `surfaceVariant` and `outline` slots now wired
  through both `CelestiaColors` and the M3 `ColorScheme`
- `CelestiaColors`: added `surfaceVariant: Color` and `outline: Color` fields; both palettes
  (dark/light) supply explicit values (`#444444` / `#CCCCCC`)
- `CustomTheme.toCelestiaColors()`: derives `surfaceVariant` from `surface` at 70 % alpha
  and `outline` from the dark/light default; keeps custom-theme JSON schema backwards-compatible
- `client-core` and `client-launcher` build scripts updated with test dependencies:
  `ktor-client-mock`, `kotlinx-coroutines-test`, `mockk`, `slf4j-simple`
- Complete UI overhaul: replaced undecorated fullscreen window with native resizable window
- `AppLayout`: 3-column layout — icon-only sidebar (64dp) + content area + right panel (264dp)
- `AppSidebar`: left-edge active indicator, icon-only nav items, no labels
- `SquareServerCard`: palette-based gradients (8 color pairs), hover scale animation,
  animated action buttons overlay; icon loaded from asset dir
- `DashboardScreen`: `GridCells.Adaptive(220dp)`, removed GlassCard wrapper
- `RightPanel`: unified auth section (login form / account panel) + Hivens news placeholder
- `SettingsScreen`: removed seasonal effects dropdown section
- Auto-login on startup runs immediately without splash delay
- `client-ui`: migrated from Compose Material 2 to Material 3 (`material` → `material3`);
  `material-icons-extended` dependency retained from M2 artifact pending icon migration
- `AppSidebar`: replaced custom 64dp sidebar implementation with M3 `NavigationRail` +
  `NavigationRailItem`; active indicator, sizing and accessibility now handled by the component
- `GlassCard`: `Surface(border=…, elevation=…)` → `OutlinedCard` + `CardDefaults`
- `ConsoleWindow`: custom filter toggle chips → M3 `FilterChip`; `darkColors()`/`lightColors()`
  → `darkColorScheme()`/`lightColorScheme()`; `DropdownMenuItem` updated to text-lambda API
- `UpdateDialog`: `Dialog + Surface` → `BasicAlertDialog`
- `ServerSettingsScreen`: `TooltipArea` (M2 ExperimentalMaterialApi) → `TooltipBox` +
  `PlainTooltip`; tooltip position provider updated to
  `rememberTooltipPositionProvider(TooltipAnchorPosition.Above)`
- `LinearProgressIndicator`: `progress = value` → `progress = { value }` (M3 lambda API)
  across `LaunchControlPanel` and `UpdateDialog`
- `Divider` → `HorizontalDivider` / `VerticalDivider` throughout all screens
- `ButtonDefaults.buttonColors(backgroundColor=…)` → `containerColor=…` throughout
- `OutlinedTextFieldDefaults`: `textColor`/`backgroundColor` → `focusedTextColor`/
  `focusedContainerColor` in `RightPanel` and `ServerSettingsScreen`
- Typography tokens updated to M3 equivalents (`subtitle1→titleMedium`, `caption→bodySmall`,
  `h5→headlineSmall`, `overline→labelSmall`, etc.)
- `SquareServerCard`: hardcoded dark colors replaced with `CelestiaTheme.colors` tokens —
  card overlay, action bar, border, server name text, and badge background now adapt to
  light / dark theme
- `DashboardScreen`: launch control panel container and border use theme tokens instead of
  `Color.Black` / `Color.White` literals
- `ServerDetailScreen`: right banner block and `MissingDataWarning` background replaced with
  theme-aware colors; `MissingDataWarning` now uses a semi-transparent amber tint in both themes
- `UpdateService`: five previously-private methods (`compareVersions`, `findAssetForCurrentOS`,
  `extractChecksum`, `verifyChecksum`, `shouldCheck`) changed to `internal` visibility
  for unit-test access without exposing them in the public API
- `SettingsData`: added `isOfflineMode: Boolean` field (default `false`)
- `CredentialsManager`: credentials.json format version bumped to v2;
  fields `encryptedPassword`, `passwordIv`, `version` added
- `LauncherController`: auth and file-sync steps are now conditional on
  offline mode setting
- `AppRoot` auto-login: creates stub session from cached credentials when
  offline mode is active
- i18n: new strings added to `AppStrings`, `EnglishStrings`, `RussianStrings`,
  `GermanStrings` for offline mode, server settings, and icon upload
- `DashboardScreen`: raw `LazyVerticalGrid` replaced with `ServerGrid` component;
  servers split into `★ FAVORITES` and `AVAILABLE SERVERS` sections with full-width
  headers; adaptive column count scales better on large/ultrawide monitors
- `ServerSettingsScreen`: RAM `Slider` (Float, 30 steps) replaced with `RamSelector`
  (preset buttons + exact MB input); `ModItemRow` + `TooltipBox` replaced with
  `ModItemCard` (inline description, conflict detection); old `ModItemRow` composable
  removed
- `SettingsScreen`: added "Custom Background" and "About" shortcut cards in Interface
  and new About section; accepts `onOpenBackgroundSettings` / `onOpenAbout` callbacks
- `AppLayout`: `Row` background becomes `Color.Transparent` when custom background is
  active, allowing wallpaper to show through; sidebar includes About (`Icons.Default.Info`)
  navigation item; routes `Screen.About` and `Screen.BackgroundSettings`
- `Main.kt` / `AppRoot`: `AppLayout` wrapped in `Box { CustomBackground(); AppLayout() }`;
  `BackgroundManager` initialized alongside `ThemeManager`; background settings state
  hoisted and persisted
- `AboutScreen` visual and technical refinements: replaced placeholder logo with actual
  app icon, implemented GitHub avatar fetching via Coil with high-quality downscaling
  (`FilterQuality.High`), wired dynamic library versions from `BuildConfig`, and expanded
  the System Info section to include CPU threads, physical RAM, and display resolution
- **Crash on Windows when opening file picker**: added ProGuard keep rules for JNA
  to prevent the optimizer from stripping methods required for native system dialogs.
  `exitApplication` now correctly wired through `AppRoot` → `AppLayout` → `DashboardScreen`;
- Migrated static custom background rendering from `ImageIO` to native Skia.
- Parallax mouse position tracking lifted to root containers (`Main.kt` and `BackgroundSettingsScreen.kt`) to ensure consistent global tracking across the entire window.
- Configured Coil `ImageLoader` as a global singleton in `Main.kt`, replacing localized instances in `RightPanel` and `AboutScreen`.
- **Dependencies Centralization**: All library versions (Ktor, Koin, Compose, Coroutines, etc.) are now centrally managed in the root `gradle.properties`.
- Bumped app version to `2.0.0`.
- Bumped Kotlin to `2.3.20-RC3`, Gradle to `9.4.0`, Ktor to `3.4.1`, Koin to `4.2.0-RC2`, and Compose Multiplatform to `1.11.0-alpha04`.
- Updated `FileKit` integration to use the new `FileKitDialogSettings` API for native file dialogs in settings screens.
- `Main.kt`: tray init moved to `Dispatchers.IO`; `onCloseRequest` now hides to tray
  when `TrayManager.isSupported` instead of calling `exitApplication()`

### Removed
- `SplashScreen` and `AppState.Splash`
- `CelestiaBackground`, `ShellUI`, `SeasonalEffectsLayer` canvas effects
- `LoginScreen` (logic moved into `RightPanel` as `LoginPanel`)
- `AuroraEffect.kt`, `Particle.kt` — no longer referenced
- Dead method `getNeoForgeModules()` from `GameCommandBuilder` (`@Deprecated`, never called)
- Unused `onThemeChanged: (SeasonTheme) -> Unit` parameter from `SettingsScreen`
- Leftover seasonal-effects strings from all three locales (`seasonAuto/None/Winter/NewYear/Spring/Summer/Autumn`)
- `seasonalTheme` field from `SettingsData` and corresponding entries in `AppStrings`
- `SeasonTheme.kt` (`client-core`) — entire file deleted; no consumers remain after
  seasonal-effects removal
- `IFileIntegrityService` + `FileIntegrityService` (`client-core`) — never injected;
  MD5 verification is handled internally by `FileDownloadService`; Koin binding removed
- `SettingsData` legacy credential fields: `savedUsername`, `savedUuid`, `savedAccessToken`,
  `savedFileManifest` — never read or written after session state was delegated to
  `CredentialsManager`
- `IManifestProcessorService.processManifest()` — stub returning empty `FileManifest()`,
  zero call sites; removed from interface and implementation
- `IServerListService.fetchProfiles()` — zero call sites; all consumers use
  `fetchDashboardData().thenApply { it.servers }`; removed from interface and `ServerListService`
- `single<CoroutineScope>` binding in `appModule` — `LauncherController` creates its own
  scope directly, injected binding was unreachable
- `AppStrings.splashLoading` and matching keys in `RussianStrings`, `EnglishStrings`,
  `GermanStrings` — orphaned after `SplashScreen` removal
- `NewsScreen.kt` — was marked `@Deprecated`; removed from navigation (`Screen` sealed class,
  `AppLayout` `Crossfade`); `onOpenNews` callback removed from `DashboardScreen`; news
  content is fully covered by `CompactNewsFeed` in the right panel
- `ModItemRow` composable and its `TooltipBox` wrapper from `ServerSettingsScreen` —
  replaced by `ModItemCard` component
- Compose `Tray {}` block — superseded by `TrayManager` / dorkbox

## [1.3.0] - 2026-03-06

_Initial public release._

[2.0.0]: https://github.com/Kitty-Hivens/Aura-Launcher/compare/v1.3.0...v2.0.0
[1.3.0]: https://github.com/Kitty-Hivens/Aura-Launcher/releases/tag/v1.3.0
