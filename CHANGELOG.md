# Changelog

All notable changes to Aura Launcher will be documented in this file.
The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

※ Each released entry opens with a short `### Highlights` block —
2-5 plain-English bullets summarizing what the user actually notices.
The launcher's in-app update dialog renders just the Highlights; the
detailed `### Added`/`### Changed`/`### Fixed`/`### Removed` sections
below are for the GitHub release page and CHANGELOG readers.

## [Unreleased]

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
