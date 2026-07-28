# Changelog

All notable changes to Nexira (formerly Aura Launcher) will be
documented in this file. The format is based on
[Keep a Changelog](https://keepachangelog.com/en/1.0.0/).

Note: each released entry opens with a short `### Highlights` block --
2-5 plain-English bullets summarizing what the user actually notices.
The launcher's in-app update dialog renders just the Highlights.

The detailed `### Added`/`### Changed`/`### Fixed`/`### Removed` sections
below are an engineering log. Name the actual classes, files, and
mechanism, and the reason behind the change -- not just the user-facing
feature (see 2.2.12 for the reference depth). Scale the depth to the
release weight: a fast beta can stay thin, a stable rollup carries the
full writeup consolidating its betas.

Author each entry -- version summaries and the Added/Changed/Fixed bullets --
as one physical line, no manual line wrapping. A `.md` renders the same either
way, and these notes feed the GitHub Release / in-app updater verbatim, where a
hand-wrapped line would render as a <br> staircase.

`CHANGELOG_RU.md` and `CHANGELOG_DE.md` carry the Highlights only. They
are for users, who do not read class names; the developer-level detail
lives in this English file.

## [Unreleased]

### Added
- Pack versions screen (`Screen.PackVersions`): the mirror's retained builds with channel badge, publish date and mod/asset counts, consecutive same-fingerprint rebuilds collapsed into expandable runs, and a per-build changelog computed client-side by `PackVersionDiff` -- added/updated/removed mods and assets with icons and size deltas, diffed against the previous distinct build or against the installed one. Mod identity is the stable key with a filename-stem pairing pass, so a slug-less `smrt_cache` mod's version bump reads as an update instead of a remove plus an add. Switching is compat-gated: a green preview applies directly with per-file progress in a layout-stable status row, an amber one confirms with the reconcile plan counts and user-edit conflicts; restore points move onto this screen. Reachable from the pack hero's update badge, the Library card badge, and the settings Version section.
- `SmrtManifestBuild` + `VersionChannel` model the mirror's per-build versions listing (`version_number`, `version_type`, `date_published`, `fingerprint`, counts) with the `SNAPSHOT-` legacy derive; the client also parses the manifest's `channel`/`fingerprint`, the summary's `latest_built_at`/`latest_channel`/`tier`, and `display.presence`, which renders as side badges (client only / server only / both / coremod) on optional-content toggles via a new trailing slot on `NxToggle`.
- `PackUpdateDriver` bridges the new `PackUpdateStatusHub` (background auto-updater passes plus manual check/apply reports) into the notification center: `Updated`/`Failed` toast per transition, an available build announces once per version as an action-required card whose action navigates through a new `NavRequests` mediator collected in AppRoot; Library cards gain a clickable update-available pill, and nx-ui grows `Success`/`Warning` meta-chip tones plus an `NxDiffRow` primitive so the diff colour vocabulary lives in the library.
- Nightly builds: a `Nightly` workflow cuts `v<base>-nightly<commit-count>` off dev on a 03:00 UTC schedule and pushes the tag with a PAT -- the default `GITHUB_TOKEN` cannot be used, since GitHub suppresses workflow triggers on its own pushes and the Release workflow would never fire -- and Release builds it with its review gate bypassed for `-nightly` tags while the test job still gates the artefacts. The tag's base is the version currently in flight: the highest numeric base among non-nightly tags, bumped a patch only once that base carries a final tag. git's version sort is deliberately not used to find it, as without `versionsort.suffix` it ranks `v2.3.4-beta5` above `v2.3.4` and the base would stay pinned to a prerelease after its final shipped.
- `ReleaseChannel.Nightly` classifies a `-nightly` tag, and a `nightlyChannel` flag in `SettingsData` opts into them; `UpdateService.checkForUpdate` also treats a running nightly as opted in, so an install that arrived as a nightly keeps tracking them without the flag being set. Either forces the `/releases` list fetch, because `/releases/latest` drops prereleases entirely.
- Dev-only UI-debug overlay on F9 or the `uidebug` console command (`hivens.ui.debug`): slot / widget bounds with labels and sizes, spacing rulers, a recomposition tint and a perf HUD, drawn through `LocalWidgetDecorator` / `LocalSlotChromeModifier` and gated off release builds by `ReleaseChannel.classify`.

### Changed
- The boot threshold's pixel readout always shows: the 250ms grace window that skipped it on warm boots (leaving a bare veil lift out of nowhere) is gone, a warm boot plays a condensed but complete segment sweep with a 350ms screen-time floor, and the exit is one quick beat -- full bar, a breath, then a single fast Bayer-dither lift with the readout fading just ahead of it (still a pure luminance transition; a fancier radial-wave exit was built first and cut for being too much, though `DitherVeil` keeps the wave mode). The beat is entirely frame-clock driven, so the new `ThresholdOverlayRenderTest` walks entry, mid-lift and end frames deterministically with sampled clear-ratio probes under both palettes; the recovery-restart threshold skip becomes an explicit flag so a boot finishing before the first window composition still plays (and still masks the shell's expensive first composition).
- The pack-settings window sizes purely by fraction of the app window -- the 1320x940 dp caps are gone, so a large monitor gets a proportionally large panel -- closes on Esc, and carries an identity header (avatar with initials fallback, name, installed build + channel chip, fetched best-effort so offline settings stay usable). Its Version section becomes a compact panel (installed build + channel, the mirror's latest-build line, check, follow-latest, update banner: green applies in place with per-file progress in a new layout-stable footer strip, amber routes to the versions screen); the build and snapshot tables leave the narrow pane for the versions screen.
- Mirror update detection compares version labels for inequality instead of `comparePackVersions` tuple ordering: a `SNAPSHOT-` segment tuples to zero, so every release compared newer than any channel build and `checkForUpdate` reported `UpToDate` across the whole snapshot chain. Build ordering everywhere keeps the server listing's publish-date order, and `PackUpdater` widens with `previewSwitch`/`availableBuilds`/`listSnapshots`/`rollback` so UI code plans a switch to a specific build against the interface.
- A single "Pre-releases" toggle in the Advanced settings section replaces the update-manager window's channel picker, mapping `updateChannel` between `Release` and `Beta`. The `.desktop`-entry install button moves there as a carry-over, pending a proper cross-platform OS-integration affordance.
- Mandatory-update enforcement (`mandatoryUpdatesEnabled`) defaults off, so a mandatory floor advises rather than forces unless the user opts in.
- `UpdateService.compareVersions` ranks prerelease tiers through an explicit ladder (`preview` < `alpha` < `beta` < `rc` < `nightly`) instead of comparing suffix text, which ordered them by accident of the alphabet and placed `nightly` below `preview` -- a preview install opting into nightlies was offered nothing at all. Two builds on one tier, or a suffix off the ladder, still fall through to natural token order, and a tag with no suffix keeps outranking every tier, which is what stops a nightly install from being trapped on nightlies with no way back.
- skinema 0.7.0 stops versioning the native bundles with the library: they now carry the FFmpeg build plus a repack revision (`8.1.1-1`) on their own `skinemaNatives` line. Packaging only -- no API or decode change from 0.6.2.
- Only the host platform's `skinema-natives` decode classifier ships instead of all five, leaving the bundle the running JVM can actually load.
- The pack hero's Play button becomes a low monochrome plate -- a static `#121318` on a dark theme, white on a light one, instead of the palette accent so it reads on any hero art -- with a 12dp rounded-rectangle corner (between a bare rectangle and a stadium; a square style keeps its hard edge). Its press-compress rides a graphics layer only while animating: the always-on layer resampled the whole button as an offscreen texture and softened every edge and glyph on a fractional-DPI display, where at rest it now draws straight into the window. The plate walks the launch through `IndicationCenter` -- Play starts it, a dimmed pulsing wait rides prepare/sync, a running game turns it into Exit (stop), a failure falls back to Play while the error toast carries the diagnosis -- and `HomeNewHero` / `HomeNewQuickLaunch` drop their `NxButton` for the shared `QuickLaunchButton` so the home launch widgets carry the same plate and the same states.
- The game-running notification is informational: the show-console / stop buttons are gone (control lives on the session surfaces) and the toast dismisses itself instead of pinning as action-required.
- The JVM-args builder seeds from the instance's stored `jvmArgs` instead of always the default preset, so reopening it no longer discards custom flags (a passthrough like `-Dcustomskinloader.ignorePatchFailure=true` kept getting wiped on the next Apply). New `JvmConfig.fromArgs` round-trips a stored args string back into the structured model -- recognized `-XX` flags map onto their fields GC-aware, and anything the builder doesn't model (`-D...`, `-X...`, agent flags) is preserved verbatim in the Custom tab; a preset plus passthrough flags round-trips exactly.
- The Linux AppImage drops from ~95.6 MB to ~74 MB (measured, `nightly1288` baseline). The release uber jar now ships STORED, not DEFLATE: a compressed jar is opaque to the outer squashfs pass, so ~100 MB of class data used to ride at DEFLATE ratio; stored, one squashfs-zstd pass compresses it and dedups thousands of near-identical Compose classes. `appimagetool` is pinned to `--comp zstd` at level 22 with 1 MB blocks (it defaulted to level 15 / 128 KB), and the `build-appimage.sh` step strips the ~26 foreign `libjnidispatch` blobs FileKit's JNA never loads on a linux-x86-64 host (host dispatcher and every JNA Java class kept). Same "do not double-compress" reasoning the jlink runtime already used, now applied to the app jar. A gradle post-process on `packageReleaseUberJarForCurrentOS` does the store-and-unsign rewrite (it also carries the BouncyCastle signature strip that the retired ProGuard step used to do).
- The Home view defaults to the modern widget-composed surface (`HomeView.New`), renamed from "New (prototype)" to "Modern" (RU "Современный") and moved to the head of the Home-view picker; the classic Dashboard and the Library-first IA stay one click away. `SettingsData.homeView` defaults from `Classic` to `New`; an install with a saved choice keeps it.
- The base-URL override reads `-Dnexira.conduit.baseurl` (was `aura.conduit.baseurl`), the last system property the rename missed. No fallback on the old name: it is documented nowhere outside the source and referenced by no script or workflow. The `<dataDir>/server-config.json` path is unchanged.
- The diagnostic bundle is written as `nexira-diagnostic-<session>-<timestamp>.zip`, and the AppImage packaging profile exports `NEXIRA_JLINK_MODULES` / `NEXIRA_JLINK_OPTIONS`. Both names are write-only, so old bundles keep their filename and nothing needs migrating.

### Fixed
- The login panel's "remember me" box forgot its own setting: it was session-local state defaulting to on, so unticking it held for that one sign-in and the next start silently re-armed saving. It now seeds from `SettingsData.saveCredentials` -- a persisted field that had never been read or written -- and records the flip immediately, so the choice survives a restart even if the user closes the window without signing in. Credentials already stored are untouched: turning the box off stops future saves and nothing else.
- A still-image custom background re-decoded its full-resolution source through Skia on every launch -- a 4K wallpaper is tens of MB -- so the background visibly reloaded each start. `CustomBackground` now downscales an oversized source to the display height once and caches the shrunk PNG under `background-cache/` keyed on (path, mtime, height), evicting the source's older copies (one live file per source); later launches decode that small file. A source already within the display height decodes directly, uncached.
- The wallpaper downscale target read AWT's logical `screenSize` height, so on a HiDPI / scaled display (mac Retina, Windows scaling, fractional XWayland) both the new still-image cache and the existing video transcode shrank the wallpaper below the framebuffer and it rendered soft. Both now target the tallest physical-pixel height across monitors (`physicalScreenHeight`, from the display's scale transform), which also keys the cache so a monitor or scale change re-caches.
- Only real video containers got the downscale-on-ingest; an oversized animated background (GIF, animated PNG/WebP) played its full-resolution source through Skinema every launch. The picker now classifies the pick with `backgroundMediaKind` and routes every time-based source through the transcode, so a 4K animated wallpaper plays a display-height cached MP4 (verified: a 40x2000 GIF transcodes to 28x1440). A source within the display height, and every still, is left untouched.
- A custom background flashed the bare grey window default while it decoded: the shell painted no base behind the wallpaper, and the content background turns transparent the moment the image file exists -- before the painter has a first frame. The shell now fills the theme surface behind the wallpaper, so the gap reads as the theme background instead of raw grey.
- `ContentScanCache` no longer dies on big mod icons: a multi-megabyte forge `logoFile` JSON-encoded as a number array pushed a single Xodus loggable past the 8 MB log file, `put` failed with `TooBigLoggableException` on every scan, and the cache never populated -- every Content-tab open re-parsed and re-failed. Icons now serialize as Base64, the scanner routes them through an `IconProcessor` seam (interface in core, ImageIO implementation bound by the UI module with a decode-bomb ceiling, so the engine keeps zero awt imports) that fits them into 128 px, and `put` retries an oversized entry without its icon before skipping; pre-Base64 entries decode-fail into plain misses and rewrite themselves.
- The versions-listing client parses the mirror's current `latest` + `builds[]` shape; it still read the retired `versions[]` array, which decoded to an empty list under `ignoreUnknownKeys`, so the build switcher silently degraded to the single latest build.
- A nightly install with pre-releases off fell back to `/releases/latest`, which drops prereleases, so it never saw a newer nightly and would silently stop updating; the nightly opt-in now forces the list fetch as well as the candidate filter.
- Windows arm64 hosts resolved the `decode-windows-x64` native bundle and handed x86_64 DLLs to an arm64 JVM, which cannot load them; the host-classifier switch now selects `decode-windows-arm64` when the host reports aarch64.
- `SmartyModPlanner` logged the same inert info line whether a server manifest carried no Smarty at all or carried a Smarty-looking jar that no active name matched, leaving the surveillance mod running unnoticed. The miss now warns on its own, naming the unmatched jar and pointing at `smarty_names` in `smrt-helper.json`; the name match itself is fixed by descriptor data on the mirror (#413).
- Joining an SC-bound pack's server on modern Minecraft died as an invalid session even with the auth routed to SC, twice over. First, modern authlib (6.x+) ignores the legacy redirect properties unless `minecraft.api.session.host` and `minecraft.api.services.host` are BOTH set -- the join silently went to Mojang's session server -- and SC serves its modern session API only on the bare host over plain http (the https and `/launcher/` variants 404; SC's own patched authlib hardcodes the same bare-http host), so `GameCommandBuilder` now emits the full four-property redirect with the session+services pair on the bare host. Second, SC answers a successful join with HTTP 200 and the profile as the body where Yggdrasil answers 204 empty, which vanilla `joinServer` fails to parse and aborts AFTER the server already accepted the join; `AuthlibRedirectAgent` now nops that rethrow at class-load -- the same swallow SC's patched jar ships -- verified against the real authlib 6.0.54 / 7.0.63 / 9.0.75 bytecode under `-Xverify:all`, with legacy authlib passing through untouched and a moved modern shape leaving a stderr breadcrumb.
- CustomSkinLoader mis-detected the Minecraft version on a modern (NeoForge) pack and mis-patched skins ("Patch ... matched protocol 0 but did not modify any bytecode"). It reads the version from `version.json` as a classpath resource, but modern Forge/NeoForge load the class-bearing client off the module path, so the flat `-cp` carried no `version.json`; its only fallback (the `NETWORK_PROTOCOL_VERSION` field of `net/minecraft/realms/RealmsSharedConstants`) is gone in 1.21.1 (the class is now `net/minecraft/SharedConstants`), so it fell back to "version 0" (pre-1.14) and applied a SkinManager patch that matched nothing. `ResolvedRuntime` gains `clientResourcesJar`, the provisioner points it at the installer's resources-only `client-<neoform>-extra.jar` (`version.json` + assets, zero classes), and `modernClasspath` appends it -- restoring the resource on `-cp` as the official launcher does, without adding a second `minecraft` module (why the class-bearing client stays off `-cp`).
- Every player on an SC server rendered as a default skin on modern Minecraft even after the join worked: SC serves texture properties with a one-byte dummy signature, so modern authlib's `unpackTextures` verified it, got `SignatureState.INVALID`, and the client's `SkinManager` dropped the skin. `AuthlibRedirectAgent` now replicates SC's own patch -- it rewrites the opening `getPropertySignatureState(property)` call in `unpackTextures` to a constant `SignatureState.SIGNED` (`aload_0`/`aload_1`/invoke to `nop`/`nop`/`getstatic`), byte-identical across authlib 6.0.54 / 7.0.63 / 9.0.75, reusing the pool's existing SIGNED field and linking under `-Xverify:all`. The texture-domain whitelist and http-scheme redirect were already in place, so only the signature verdict changes.
- The release distributable crashed on boot the moment any Xodus environment opened (`SmrtPackCaches` first, cascading through the mirror Koin graph): Xodus registers a Standard MBean whose implementation class must, by name, implement an `EnvironmentConfigMBean` interface found only via JMX reflection. The release ProGuard shrink pass had no static reference to reach it and stripped it, so registration failed with `NotCompliantMBeanException` and `Environments.newInstance` threw. Both production environments (`CacheFactory`, `XodusPackRepository`) now open with `EnvironmentConfig().setManagementEnabled(false)` -- nothing consumes those JMX beans, so registration is skipped outright. This stays as a guard even after the shrinker's removal (below): the dev classpath kept the interface, which is why it only ever surfaced in the packaged build.

### Removed
- The SmartyCraft SOCKS proxy channel, entirely: `ChannelRouter` and its direct-then-proxy fallback, the proxied and insecure-proxied OkHttp clients, the JVM-wide `Authenticator` that answered the SOCKS auth challenge, the four `proxyHost`/`proxyPort`/`proxyUser`/`proxyPass` fields in `ServerProtocolConfig` with their shipped defaults, the `forceProxyMode` setting and its Settings toggle, and `NetworkState.forceProxyState`. Nothing outside `*.smartycraft.ru` ever used the hop, and that traffic has connected directly by default since the channel was introduced -- the proxy only carried an `IOException` fallback that had stopped authenticating, an opt-in toggle, and, unintentionally, every request made under an SSL bypass, since the trust-all client also pinned the SOCKS route. Accepting a certificate warning therefore diverted the user onto a dead proxy. `SmartycraftV1Protocol` and `LauncherHashCache` now take an `HttpClientProvider` directly, and the remaining channel decision is one branch: the trust-all client while a live bypass is held for the host, the direct client otherwise. A user who cannot reach the host directly has no in-launcher route left; that case now needs a VPN or a system proxy. The proxy password also stops being published in `docs/dev/smartycraft-v1-protocol.md`.
- The update-manager window and its backend: manual version pick, rollback to an older release (`UpdateService.prepareUpdate`), the per-channel release listing (`listReleases` + `ReleaseEntry`), and build-from-source for the `dev` / `git` channels (`SourceBuildService`). The tier model reaches the same builds through the Pre-releases toggle and the nightly flag, but rolling back or building from source now needs a manual install.
- Unused `coil-compose` / `coil-network-okhttp` catalog aliases: nothing resolved them, since the KMP source set must exclude Coil's skiko copy and `implementation(<catalog accessor>) { ... }` is rejected there (a Provider where a String is expected), so `client-ui` builds the coordinates off `libs.versions.coil`.
- ProGuard and `compose-desktop.pro`. It only shrank (never obfuscated -- the build is GPL and readable stack traces are worth more than hidden names), and against a keep-heavy config over a reflection- and FFI-heavy app that shrink was worth ~4 MB of the final AppImage while costing a recurring class of release-only crashes: JMX MBeans, `ServiceLoader` providers, Panama upcall stubs, and the Xodus boot crash above, each patched with a bespoke keep rule. Storing the uber jar recovers more size than the shrink ever saved, so the whole pass and its 250-line keep file are gone; `buildTypes.release.proguard { isEnabled = false }`.

## [2.4.0-preview] - 2026-07-14

2.4.0 opens the launcher onto the wider modding world. A new Browse tab searches and installs Modrinth modpacks, imports a `.mrpack` / a CurseForge zip / a foreign launcher's instance, or builds a pack from scratch; a Wardrobe manages your skins and capes over a reworked 3D character stack; the launcher can follow your desktop's colour scheme; and a boot screen plus a recovery mode carry a start that goes wrong. Underneath, the whole interface moves onto a single `:nx-ui` design system, the launch engine splits into headless modules with a native CLI, and the build moves to Java 26. Microsoft / multi-account infrastructure lands but stays gated off pending a later release.

### Highlights
- **This is a preview**. 2.4.0 is a large, fast-moving release shipped early as a preview -- expect rough edges, and please report anything broken on the issue tracker.
- **Browse and install modpacks**. A new Browse tab searches Modrinth's modpacks, renders their descriptions inside the launcher, and installs one in a click -- and you can import a `.mrpack` or a CurseForge zip, or start an empty pack from scratch.
- **A wardrobe for your skins**. A new Wardrobe keeps your skins as small 3D figures, applies one to SmartyCraft, picks a cape, or starts from the game's default set -- your character's look in one place.
- **The launcher follows your desktop**. It can track your system's light / dark scheme on its own and tune its theme to your wallpaper's brightness, with a new appearance studio gathering the background and look controls.
- **A boot screen and a recovery mode**. A quick boot screen shows while the launcher starts; if something goes wrong, hold Shift (or pass `--recovery`) to disable a misbehaving part or reset it -- no reinstall.
- **One consistent interface**. The whole UI moved onto a single design system -- surfaces, buttons, menus and settings sections share the same shapes, spacing and icons, and stay legible with or without a wallpaper.
- **Packs show what they're doing**. A pack card now carries a live launch state (preparing / downloading / running), and a partial import says which mods still need a manual download instead of looking like an empty pack.

### Added
- Modrinth DTOs relocate to `hivens.core.api.dto.modrinth` and a dedicated `ModrinthClient` (+ `ModrinthCaches`) splits out of `SmrtPackClient` for Modrinth's keyless `/v2` API, so Modrinth is a first-class source rather than a mirror-manifest resolver.
- Source-neutral pack catalogue: `IPackCatalogueService` (search / details / versions) over `CataloguePack` / `CataloguePackDetails` / `CataloguePackVersion`, with `ModrinthPackCatalogue` (modpack search, project body / gallery / categories, the `.mrpack`-primary version list) and `MirrorPackCatalogue` wrapping the existing mirror client, indexed by origin in `PackCatalogueRegistry`.
- `PackInstallCoordinator` installs a catalogue `(CataloguePack, CataloguePackVersion)` by origin -- mirror packs sync the version-pinned manifest via `PackInstaller`, Modrinth packs download the version's `.mrpack` and install via `MrpackInstaller` (an `MrpackSource` stamps origin=Modrinth + project / version so a later update flow can find newer versions).
- Browse gains a Hivens / Modrinth source switcher and a source-neutral `BrowsePackCard`, and a `ModrinthPackDetailScreen` renders a project natively (hero, body, gallery, version list) and installs a version through the coordinator into Library.
- In-tree pack-description renderer (`hivens.ui.render`): converts body markdown to HTML via `org.jetbrains:markdown`, parses with jsoup, and paints a known tag + inline-CSS subset (color, text-align, links, images, lists, tables, code, emphasis, `<center>`, column-fit block images) to Compose, degrading unknown markup to its text -- replacing the CommonMark-only markdown-m3 that showed embedded HTML as broken.
- App-scoped `PackInstallService` + `InstallDriver`: a catalogue install runs on the shared process scope and publishes a `StateFlow` of per-pack snapshots into the notification center, so leaving the Browse detail no longer cancels the download and the screen re-attaches on return; a reserve-dir hook deletes exactly a cancelled install's partial instance directory.
- The version picker virtualizes onto a `LazyColumn` (a pack with hundreds of versions no longer stalls composing every row), and `PuppetClick` ids land on the source tabs, cards, install glyph and version rows for end-to-end coverage of the install flow.
- `.mrpack` import: `PackImportService` sniffs a picked archive by its index entry (`detectPackArchiveKind`) -- `modrinth.index.json` installs a Modrinth `.mrpack` as a Local instance -- and the Import action lives bottom-right on Library where adding to the collection belongs.
- CurseForge `.zip` import: `CurseForgeZipInstaller` installs a CF export's overrides + provisions the runtime and records the API-gated `files[]` count on the instance notes (Nexira ships no CurseForge API key, so the referenced project / file ids stay unresolved and a tracked CF origin would promise undeliverable updates -- the instance is Local).
- Foreign-launcher discovery: `LauncherRootLocator` resolves each launcher's data root across native XDG, Flatpak and Snap layouts; `LauncherInstanceSource` discovers instances from vanilla / TLauncher, Modrinth App, Prism / MultiMC and FTB App; `LauncherImportService` aggregates them and isolates a single source's failure.
- Import engine: `ForeignInstanceImporter` copies a discovered instance into a Nexira Local pack and hardlinks a vanilla-layout runtime into the shared assets / libraries roots (client jar remapped to the `net.minecraft:minecraft` coordinate) to skip the multi-GB re-download, falling back to a copy across filesystems -- landed infrastructure, registered in DI but with no client-ui entry point yet.
- Create a local pack from scratch: `LocalPackCreator` builds a registered, launchable Local `PackInstance` from a name + Minecraft version + loader (+ optional loader version), seeding `mods/` / `config/` and provisioning the runtime.
- The Library "+" opens New local pack / Import; the create dialog (opaque `NxSurface` card, `NxField`, `NxChoiceChip` loaders in a wrapping `FlowRow`, `NxContextMenu` FAB menu, `NxButton` actions) runs through the app-scoped `PackInstallService` with a filtered Mojang-manifest version picker (`RuntimeProvisioner.availableMinecraftVersions()`).
- Create polish: a releases-first version list with a Show snapshots toggle, and an optional name carrying a smart placeholder derived from the loader + version ("Fabric 1.20.1").
- Wardrobe screen: a top-level skins-and-capes workspace reached from a new left-rail entry between Profile and Settings, seeded into existing layouts by a v4 -> v5 migration.
- On-disk provider-agnostic `SkinLibrary` at `<dataDir>/skins` with a `library.json` index, per-kind Skin | Cape lists, newest-first, tolerant of a missing / corrupt index or a png deleted behind a live entry.
- Wardrobe library grid: live 3D busts (`SkinView3D` Bust framing) with a leading "+" import tile under a Saved header, import / preview / apply-to-SmartyCraft / delete, applied history (`lastAppliedAt` + an active badge on the most-recent card).
- Capes as a second `SkinLibrary` kind: a Capes section imports a cape PNG and applies it through `uploadCloak`, relabelled "Set clan cape" (leader-only, clan-wide) since an SC cape is a clan property, not a personal feature.
- Default skins: a read-only section reads Minecraft's nine vanilla skins out of a provisioned client jar (`DefaultSkinProvider`, `assets/.../player/{wide,slim}` on 1.19.4+, legacy Steve / Alex fallback) and caches them under `skin-cache/defaults` -- their textures stay Mojang's, none bundled.
- Auto-import the current server skin into Saved on open, deduplicated by a pixel-content hash (server + cache re-encode, so byte comparison would pile up copies); file imports carry the hash too so they dedup against it.
- The preview model wears the selected cape: a cape scene node (10x16x1 box, vanilla flip, seated behind the jacket plane) swaps onto the rig's Body part so it follows torso posing and the orbit.
- A pose picker (stand / wave / sit / hide-face / walk) on the rig presets via `NxChoiceChip`, with the view state hoisted so a pose survives switching skins.
- Depth-buffered software rasterizer (`render3d/Raster.kt`): textured triangles with a per-pixel depth buffer, an opaque (depth-test + write, alpha-tested) then a far-to-near translucent pass, and a top-left fill rule so a shared edge is neither doubled nor gapped.
- `SkinView3D` projects each model face to two textured triangles through the rasterizer, and `drawWithCache` caches the frame so a static wardrobe grid rasterizes once per (skin, size) instead of every frame.
- `hivens.ui.scene3d` geometry vocabulary: `Vec3` / `Pt2` / `UvRect` / `Face` plus a tested 3x4 affine `Transform3` (factories, `aboutPivot`, composition) -- the substrate for the rig and scene graph.
- Mutable scene graph: `Node` / `Mesh` with cycle-guarded reparenting, `collectTriBatches` (accumulated world transforms, sequential rotate / project kept bit-identical to the flat renderer, texture-keyed batches) and a batched `rasterize(batches)` over one shared depth buffer with a global translucent sort.
- Posable `SkinRig`: a two-level node tree with vanilla pivots and rotation order, `Pose` per-part Euler angles with additive layering and per-channel lerp, presets Stand / Wave / Sit / TurnAway / FaceCover, pinned bit-identical at rest to the flat renderer by `RenderParityTest`.
- Time-driven pose animation: `PoseSource` (deterministic pose-of-time), procedural walk gait and breathing idle (all neutral at phase zero so motion-off freezes clean), a closed-form underdamped spring, and `PoseAnimator` tweened retargeting with `snapTo` and `isSettled`.
- Compose scene host: `Scene3DState` invalidates the draw via a revision counter without recomposing, `Scene3DView` renders through a caller-supplied `OrthoCamera` (yaw / pitch orbit form with `worldBounds` + `fitOrtho` bounds fitting) with optional drag orbit; `SkinView3D` re-hosts on it via a hoisted `SkinViewState`.
- Supersampled anti-aliasing: `renderScene` rasterizes at Nx and box-resolves in premultiplied space (default 2x) so silhouettes stop stair-stepping while texture sampling stays nearest for crisp pixel-art interiors.
- The Profile account hero idles on the breathing cycle instead of turntabling; drag still orbits, motion-off styles render the neutral static pose (the wardrobe preview keeps the spin).
- `PackInstance` gains `iconUrl` / `bannerUrl` captured from the catalogue at install and `playtimeSeconds` summed on exit; `PackArtResolver`, `ImageGallery`, `PixelArtBackground` and `RememberPackArt` render native covers across the Browse / Library cards, pack detail and worlds tab.
- The default home swaps the quicklaunch block for an art-backed hero card (continue-target banner + version + played hours + launch), layout schema v6 -> v7 migrating only an untouched default home; the recent row's tiles adopt the same three-layer mini-card.
- `PackCard` shows a corner launch-state pill (Preparing / download % / Running / Failed) read from `IndicationCenter.launchIndication`, and `PackDetail` renders the `PackInstance.notes` provenance banner (so a partial CurseForge import lists the mods still needing a manual download instead of reading empty).
- Design system extracted into a new `:nx-ui` Compose leaf module (package `hivens.ui.nx`) at the bottom of the UI graph: tokens, theme / palette (`PaletteEngine` / `PaletteSeed`), and glow / pixel effects.
- Typography + fonts move to `:nx-ui` with their own composeResources under a distinct `Res` package so the two `Res` objects never collide on the classpath.
- A Material Symbols Rounded subset font rendered via `Symbol(NxIcon.X)` replaces the 36 MB material-icons-extended with a ~181 KB cut, plus `tools/icons` (manifest + generator) and the typed `NxIcon` catalog; outlined by default, filled on active via the FILL axis.
- `NxSurface` body-owning primitive: an opaque tonal body + luminance bevel + optional glass coat picked by depth level, so a surface no longer collapses into the page when the coat comes off (light theme, no wallpaper, or intensity 0); `NxCard` / `NxPanel` wrap it.
- `FrostSurface` moves to `:nx-ui` with the wallpaper dependency inverted through `LocalBackdropPainter`, completing the extraction (tokens, palette, typography, icons, primitives, effects and surfaces all in the leaf module).
- Library-owned form primitives: `NxSection`, `NxSlider` (bounded track), `NxField`, `NxColorField` (`parseHexOrNull`), `NxToggle`, `NxChoiceChip`, `NxIconButton` -- the controls the settings screens were re-deciding by hand.
- `NxNavRow` (self-plated settings-shortcut row) and `NxRow` (in-plane clickable row) join the primitive set.
- `NxScrollbar` (auto-hiding overlay bar), `NxTooltip` (tokened `TooltipArea`), `NxDraggable` (drag gesture driving the native OS cursor), and `NxColorSurface` (arbitrary colour-is-data fill) join the primitive set.
- `NxContextMenu` gains a cursor-anchored right-click overload and an opaque `NxSurface` plate; shared role components `NxMetaChip` / `NxSourceBadge` / `NxCalloutBanner` / `NxSectionHeader` move into `:nx-ui` and every call site routes through them.
- `NxKebabButton` + a per-row overflow menu on the Library content tab (Details / Open page / Delete), Details reading `fabric.mod.json` / `quilt.mod.json` / `mods.toml` first so it works offline, filled by the hash-resolved Modrinth project.
- The `Flexible` event-decorator layer (`FlexibleEvent` / `FlexibleHost` / `FlexibleSignalBus`, with `onSignal`): the superstructure the April Fools fleeing-button becomes one event of, a DI-free trigger / listener substrate a rule engine, achievements and idle cinematics can share.
- Boot threshold: the window goes up before Koin (`ShellHost` owns one undecorated Window, settings peeked from `settings.json` pre-Koin) with a tier-0 `ThresholdOverlay` readout while the slow bootstrap runs on a background thread; content switches inside one window so there is no XWayland remap, and the single-instance lock moves pre-window.
- The threshold is drawn in an 8-bit grammar: a block frame with cut corners, a segmented fill on a pixel grid, an honest percent counter and stage labels in bundled Press Start 2P (OFL, full Cyrillic).
- The veil lifts by an ordered-Bayer-dither `RuntimeEffect` (the first SkSL in the tree) dissolving the dark veil off the live shell cell by cell, degrading to a plain alpha ramp on shader compile failure.
- The threshold honours the user's theme: the settings peek reads `isDarkTheme` and renders one of two palettes (dark, or the Game Boy pale-paper light), the dither colour a shader uniform so the dissolve matches the field.
- Boot recovery: a `ModuleId` registry + `SettingsData.disabledModules` read at each system module's init (keyring-off drops the vault to its file tier, a process-wide `SkinemaGate` suppresses skinema, tray / notify skip individually), stored as stable string ids so an unknown id is ignored.
- `RecoveryEntry` resolves a pre-shell recovery request from `NEXIRA_RECOVERY` / `--recovery` / a marker file, and a user request skips the whole boot (Koin, vault, network) so a broken module cannot take the recovery surface down with it.
- `RecoveryWindow` (module toggles + layout / customization / settings resets + relaunch via `AppRelauncher`, `RecoveryIo` doing the Koin-free read-modify-write) replaces the quit-only safe-mode stand-in; a "restart in recovery mode" action lands in Diagnostics.
- A "Start in recovery" `.desktop` Action (Linux) passes `--recovery` through the AppImage AppRun into the resolver.
- Hold Shift at launch to enter recovery: `HoldKeyProbe` reads the X keymap over its own short-lived libX11 connection (no AWT toolkit), working on X11 and XWayland; a missing X server / libX11 or native Wayland just yields no gesture.
- A class-linkage crash (`LinkageError`) fast-tracks safe mode one crash sooner, keeping a single retry for a recompile-while-running recovery.
- Appearance studio: the Custom Background screen is reworked onto nx-ui and retitled Appearance -- the fake preview and its second video pipeline are gone (the live shell IS the preview), with a wallpaper island and a theme-axis island flanking it.
- Follow-the-OS-scheme lands as one explicit `ThemeMode` (Manual / System / Wallpaper); `SystemTheme` reads the scheme via the XDG portal `Settings.Read` (Windows registry / macOS defaults elsewhere), surfacing a disabled System chip with a caption where no portal backend exists.
- The OS-scheme follow reacts instantly via a portal `SettingChanged` gdbus monitor (filtered to the canonical color-scheme line), falling back to a 5s poll where the signal cannot start; System is the default where the scheme is readable.
- Match-theme-to-wallpaper-brightness: an opt-in `ThemeMode` sets dark / light from the wallpaper's average luma, re-evaluated on wallpaper change.
- A working wallpaper saturation slider drives a `ColorMatrix` filter over the static painter and every video frame, mirrored through `BackdropState` so a frosted panel cannot drift from the real draw.
- A transparent `FrostTier.Clear` puts the left rail + top bar on the same body-owning `NxSurface` as the right panel, so one matte tier means one thing across the chrome.
- Offline play (shipped): `OfflineAuthProvider` mints a local session (vanilla `OfflinePlayer:<name>` UUID, blank token, `offline=true`) with no network, registered in an `AuthProviderRegistry` that reports membership so a gate enforces a requirement only for a provider it can satisfy.
- `PackAuthRouter` derives a pack's auth requirement by origin, and `preparePackAuth` gates only a satisfiable provider -- so a Modrinth / CurseForge / vanilla pack launches with the current session instead of being blocked; a real offline launch emits `--userType legacy` with the vanilla offline UUID, and the chosen name persists in `SettingsData.offlinePlayerName`.
- A Play offline button in the login panel signs in from the username field with no network, and an auth-failed pack launch (missing provider, expired 2FA, authlib unavailable) offers a Play-offline action -- singleplayer only, an SC-bound pack still cannot join its server offline.
- Headless native CLI: a new `:client-cli` drives the launch pipeline (auth / resolve / download-verify / JRE / runtime / launch) without Compose and compiles to a GraalVM / Liberica-NIK native Linux binary via `nexira.native-image` (a buildSrc convention plugin, vendor-agnostic toolchain resolution, committed reachability metadata including the libvault D-Bus downcalls); `LauncherBootstrap.preBootHeadless` keeps AWT / Swing out of the reachable graph, and the Compose GUI stays on the JVM.
- Video wallpaper GPU pipeline: `HwAccel.AUTO` hardware decode (VAAPI / D3D11VA / VideoToolbox, a `BackgroundSettings.hardwareDecode` escape hatch) and `BackgroundOptimizer` transcoding an over-tall video to the display height once at pick time, so a 4K wallpaper plays at display resolution instead of paying full source cost every frame.
- Microsoft / multi-account infrastructure landed but gated off (no release ships a client id, so the provider never registers and the profile hides its Microsoft category): `MsaConfig` + `MsaConfigLoader` with blank `clientId` = disabled as the single gate, and `MsaAuthProvider` in a new `:client-auth-microsoft` (OAuth 2.0 device-code -> Xbox Live / XSTS -> Minecraft-services token exchange, a `RefreshableAuthProvider` for silent refresh) unit-tested against mocked responses, registered and enforced only when a client id is configured.
- A v6 provider-keyed multi-account `CredentialsManager` (composite `provider:accountId:field` vault keys, migrating v5 into one SmartyCraft account) with per-provider session resolution (`accountFor`) so an SC-bound launch uses the SC identity even when another provider's account coexists -- landed but exercised only behind the gate above; an offline identity stays in settings, not an account.
- Profile per-provider work behind the same gate: a device-code sign-in control (`MicrosoftSignInButton`), per-provider profile sections, an account roster with add / remove, a face selector (`preferredFaceProvider`) and licence-priority `primarySession` -- rendering as a single SmartyCraft section with no client id.
- `NavBackStack` replaces the single-screen nav state with real back history through detail screens, and the Lotus Dark theme preset joins the picker.

### Changed
- Design Constitution surface migration (#378): every settings section moves onto nx-ui island planes -- `NxSection` / `NxSlider` / `NxField` / `NxColorField` / `NxToggle` / `NxChoiceChip` / `NxIconButton` / `NxNavRow` / `NxRow` replace the per-screen `settingsRowBackground` + orphan-divider `SettingsSectionTitle` + raw Material controls across Console, Smarty / Experimental / Network / Advanced, Appearance and Diagnostics, one opaque body plane per section, and the inter-section gap unifies on 16dp.
- `NxButton`'s four #182 emphasis roles (Primary / Secondary / Tertiary / Destructive) become the single button across the app: the login, profile, Diagnostics, ServerSettings, Migration, Play, Wardrobe, Microsoft, theme-apply and update-download call sites port off `af.ChaosButton` / `CelestiaButton`, plus the trivial Material `Button` sites.
- The app-wide indication switches to a shape-correct `ThemeStateLayer` (`LocalIndication`, with `LocalContentColor` anchored to the palette's textPrimary) so every bare clickable / selectable / toggleable gets container-matching hover / press / focus feedback, including keyboard focus.
- Editor: the slot orientation switcher moves off the layout flow onto a selection + `NxContextMenu` model (`LocalSlotChromeModifier` replaces the flow-child decorator, production layout byte-for-byte unchanged).
- Editor: the weight drag-divider is removed so Column / Row are pure stacks, killing the "spacing looks wider in the editor" gap (the weight field and its render branch stay, since the shell's center region fills via weight in the default layout).
- Editor: widget composition retains across Ctrl+E via per-instance `movableContentOf`, so the decorator swap moves the subtree instead of disposing it and each widget keeps its loaded state (the right panel likewise).
- Editor: widget actions move from the hover affordance buttons to a right-click `NxContextMenu` at the cursor, and the flow-slot SE handle bounds the widget (`widthIn` / `heightIn` max) instead of forcing a fixed size that inflated the box past its content.
- Editor: a cube-grid model (`SlotOrientation.CubeGrid`, `GridCell`, snap-to-cell placement + rectangle render + LMB-drag move) is added but ships hidden from the slot layout menu pending a proper launcher cell-grid.
- Module split -- the launch engine goes headless: `client-launcher` drops all AWT / Swing (a ui `GuiBootstrap` composes the GUI boot pipeline, `CrashReporter` keeps only headless report generation), media (`YtDlpService` / `VideoCacheService`) extracts into `:client-media`, the tray into `:client-tray`, the v6 credential store into `:client-auth` behind an `AccountStore` contract, and widget-layout persistence + pack content-view transforms (`DepGraphResolver` / `ModRoleGrouper` / `ModIconResolver`) move to the UI layer / `client-core`.
- Light-theme and frost coherence: `glassSurfaceAlpha` / `scaledAlpha` and a bare `FrostSurface` Fill go opaque on light (a light source over a busy wallpaper has no good alpha), a `FrostTier.Clear` plus `NxSurface` put the left rail + top bar on the same body-owning surface as the right panel, and a theme switch swaps the palette in one recomposition (`LocalNxColors` is static) with the circular reveal carrying the transition.
- The platform colour / theme types rename `Celestia*` -> `Nx*` across 134 files, Celestia and Brut surviving only as the two `StyleSpec` presets.
- Optional-mod toggles key on a stable id (`SmrtModEntry.slug` / Modrinth `project_id` / filename fallback) instead of the version-bearing jar filename, so a pack bump no longer orphans the toggle; `ServerSource` pins `@SerialName` wire names, and `InstanceProfile.optionalModsState` becomes an immutable `Map`.
- Credentials store on `dev.hivens:libvault` 0.2.1 (`credentials.json` drops to a v5 metadata-only record; a keyring locked at startup degrades to the encrypted-file tier without prompting), the startup keyring probe is deadline-bounded, the keyring open is deferred off the first-composition reveal (`LazySecretVault`), and secrets are redacted in `SessionData.toString`.
- UI sans switches from Google Sans Flex to Roboto Flex (Latin-only Google Sans fell back for Cyrillic UI text), sliced to four static weights covering Latin / Cyrillic / Greek.
- Custom window chrome: undecorated-window maximize / restore is owned by `WindowMaximizer` driven off the real WM state (EWMH extendedState + geometry listeners, `Toolkit.isFrameStateSupported` hiding the glyph where unsupported) instead of the racy AWT `MAXIMIZED_BOTH`, and the custom drag anchors on the absolute screen cursor instead of a local delta that oscillated on every floating WM.
- Build toolchain moves to Java 26 (`gradle-daemon-jvm.properties`, `jvmToolchain(26)` on the KMP modules that skip the java plugin, foojay 1.0.0 dropping the IBM_SEMERU reference), Gradle 9.5.1 -> 9.6.1; `authlib-agent` / `profiler-agent` stay pinned to Java 8 bytecode for the legacy game JVM.
- Dependency bumps: ktor 3.5.1, koin 4.2.2, coil 3.4.0 -> 3.5.0, compose 1.11.1, ksp 2.3.10, skinema 0.6.0 -> 0.6.2 (GPU decode + Windows liblzma), `libtray` / `libnotify` 0.1.2 + `libvault` 0.2.1 (private D-Bus connections fixing cold-start tray render), material-color-utilities 4.1.1 -> 5.0.0, jsoup 1.22.2, markdown 0.7.7; junit held on 5.x (6.x drops the Java 8 agents).
- Build hygiene and perf: build-artifact jars drop their version so a stale jar cannot shadow newer classes, ProGuard dontwarns jsoup's optional re2j, test JVMs gain `--enable-native-access=ALL-UNNAMED`, `MALLOC_ARENA_MAX=2` caps the native video pipeline's glibc arena RSS (~2.6 -> ~1.5 GB), the skin rasterize moves off the draw thread with reused buffers, and the frame present states `skiko.vsync.enabled` (dropping the dead `skiko.fps.limit`).
- Docs site moves to Astro 7 on Node 24, and Dependabot runs weekly grouped updates targeting `dev`.
- auto-login retries a network-shaped failure on a capped ladder (`AuthException.isNetworkError`, a classified `Resolution`) so a DNS outage no longer kills auth for the app lifetime, and the pack-centric Home works signed-out (offline-name quick-launch, else a route to Profile sign-in).
- The Smarty open-smrt swap is scoped to a raw SmartyCraft server sync only since packs carry their own mods, its toggle titles normalize to declarative labels, and the last raw Material `DropdownMenu`s (prop-panel choice, language, log session) route through `NxContextMenu`.
- The left-rail active-item backing strengthens (0.13 -> 0.22 alpha) so a selected tab reads over the wallpaper, the settings inter-section gap unifies on 16dp (Console was 10dp), and editor prop-panel and settings side-nav labels ellipsize instead of clipping.
- `ServerSettings` extracts a state holder (`ServerSettingsState`) out of composition, moving the profile load / save / toggle / pick IO off click lambdas onto a held scope with the pure `assembleProfile` and `applyModToggle` unit-tested.
- The right panel gains its own `ShellRightRegionProps` (dropping the dead divider toggle and flat-glass slider it never honoured) and the bundled layout seeds its floating-panel frame; the left region gains `ShellLeftRegionProps`.
- `TrayManager.updateStrings` republishes the menu and tooltip on a runtime locale switch instead of leaving them stuck in the startup language.

### Fixed
- 3D skin rendering correctness: the depth-buffered rasterizer replaces the per-face centroid painter's sort, fixing the head showing through the hat, hat faces vanishing on rotation and the wrong-texture head bottom.
- The skin second layer renders as an alpha-tested cutout drawn double-sided (no rotation flicker, no see-through crown), replacing the translucent-pass blend that ordered whole faces by centroid.
- The box bottom-face V is flipped through the UV rect (origin at the far edge, negative height, winding preserved for the cull) so every underside renders the right way round.
- A strict depth test (z > depth) plus a small overlay z-bias stop a coplanar seam-flush overlay from double-texturing over the base.
- Overlay seam faces stay flush (head no bottom growth, torso no top / bottom, limbs no top) so coplanar quads stop z-fighting at the head / body seam under the painter's-era renderer.
- Limb inner planes overlap 0.03 past the shared plane to stop cross-part depth speckle when a pose turns two limbs toward the viewer, and the FaceCover pose actually covers the face.
- `SkinView3D` disposes the native Skia `Paint` (not only the `Image`) on leaving composition.
- Render-thread crashes now actually recover: `application {}` ran with `exitProcessOnExit=true` so the JVM died before the crash was consumed / logged / reported -- set false, so the loop reports to disk + restarts the shell (or safe mode); the provoking stressor is fixed too (every opacity tick re-blurred the wallpaper because alpha sat inside the cached blur -- alpha now composites outside it and persistence is debounced 300ms onto IO).
- The uncaught-exception handler no longer infinite-loops when the crash dialog itself throws (the dialog call is guarded inside `invokeLater`).
- HTML body finish on real Modrinth pages: `<center>` content centers, `center>a>img` banner / divider runs are collected instead of vanishing to empty alt text, block images scale to the column width instead of raw px-as-dp, and heading hierarchy, inline-code chips, a blockquote accent bar and contained tables replace the undifferentiated wall.
- IO durability: atomic writes fsync the temp file and parent directory (a crash could persist the rename over still-cached bytes), a namespace clear sweeps orphaned `.json.tmp`, and provisioning uses a unique temp per writer (a concurrent launch + install could clobber a shared `<dest>.tmp`).
- A mrpack download verifies against the strongest pinned hash (sha512 over sha1, rejecting a hashless entry) with a normalized zip-slip base.
- NBT strings read / write as Java modified UTF-8 (standard UTF-8 corrupted world / server names with supplementary characters or NUL).
- A password containing whitespace no longer leaks its tail into disk logs and crash-report URLs.
- A single malformed SSL-bypass expiry drops only that entry rather than discarding the whole load, and the dropped-count log is corrected.
- A permanent 4xx on the patched-authlib fetch skips retry (was ~13s of backoff on the launch path); transient stream drops still retry.
- Loader resolution when a pack pins no version: a blank Fabric / Quilt version resolves the newest stable loader instead of a `/loader/<mc>//profile/json` 404, and the modern Forge / NeoForge installer resolves the promoted / newest build instead of a malformed URL.
- Wardrobe: cards get a fixed caption height so the squares and captions align, and the action strip is constant-height so selection or a failed upload no longer shoves the grid.
- Wardrobe: apply / import / delete stop re-decoding every card in composition (one IO snapshot + an id-keyed bitmap cache), and clan-cape eligibility resolves from the public player page (capability-gated instead of failing after the click).
- `open-smrt-network` is no longer double-injected onto packs -- a mirror pack already bundles it so FML rejected the launcher's duplicate copy; the swap is now inert unless a proprietary Smarty jar is present in the synced manifest.
- Editor: the per-widget edit outline draws inside widget bounds and shows at low alpha at rest (strengthening under the pointer), so edit mode is pixel-faithful to production and no longer reflows content by ~12dp per widget.
- Editor: the slot chrome reads its press on the Final pass and the widget detects a right-click with a raw pointer-event loop, so a right-click reaches the widget's own menu and only empty slot area opens the layout menu (a cube widget fills its cell so the press hits it).
- The nav rail's smaller Material Symbols glyphs no longer leave a dead non-clickable inter-item strip (spacing to 0, each `NavSlot` to 54dp), and the idle-icon swap drives off the Symbol FILL axis instead of a table that mapped every entry to the same icon.
- A deleted wallpaper file no longer shows a blank white window (`hasUsableImage()` gate falling back to the theme background).
- The surface border contour survives rounded corners (drawn as an overlay outside the clip via `drawOutline`).
- `NxContextMenu` is opaque (an `opaque` flag pins the body floor to 1.0, no dark-theme bleed-through), and text primitives clamp overflow within their container.
- The About screen drops a crash-prone nested scroll, and logback's per-launch status dump is silenced (`converterClass` -> `class`, which raised a WARN that dumped the whole internal status).
- The create dialog reads as a solid card (opaque `glass=false` + a deeper scrim) and unfolds on open honouring the style's motion (Brut opens instantly); the version field opens its picker on focus so no square indication pokes past its rounded shape.
- Appearance: the wallpaper is read once for both the Monet seed and the brightness average (two concurrent full `readPixels` ran the heap out), and the downscale-on-ingest cache writes a `.mp4` temp so the muxer is inferred (a `.part` temp silently failed every encode).
- The account roster no longer wipes credentials before the logout confirm, so dismissing the dialog leaves the account intact instead of stranding an empty roster.
- A pack install survives leaving the Browse detail screen (hoisted onto the app-scoped `PackInstallService`).
- Deleting a pack whose files are locked by a running game keeps the Library entry and logs the un-removed files instead of orphaning data silently.
- The preset list refreshes after the save / delete write lands (it was listed synchronously right after firing the async write, so a just-saved preset was missing).
- The right panel drops its own background repaint so its `NxSurface` tonal body shows instead of reading as the page background.
- Needless recomposition is cut in the content tab (stable per-row toggle identity), the console (a palette-free structural pass split from a cheap styling pass, both off the UI thread) and `AppRoot` / `AppShell` (side effects moved to run-once or correctly-keyed effects).
- Both pack-detail heroes (Library and Browse) trade the full-bleed square banner for the app's floated-card treatment (side gutters + cardCorner clip).

### Removed
- The experimental per-role colour and shape override layer -- `CustomizationSettings.experimentalColorOverridesEnabled` / `colorOverrides` / `styleOverrides`, the `ColorRole` and `StyleOverrides` types, `StyleSpec.applyOverrides`, and the `NxTheme` override block -- a pre-modular relic that painted one global set of colours and shapes over the whole theme; `CustomizationSettings` stays as the live store for density, glass intensity, accent override and the editor's nav-rail selection, and the dropped knobs keep applying at their stored values.
- The standalone Customization screen that hosted it: `Screen.CustomizationExtension`, `CustomizationSurface` and its density / glass-intensity / accent / reset widgets, the `AppearanceSection` entry row, the `BreadcrumbResolver` and `EditorSurfaceHost` cases, and the `customization` surface in `default-layout.json`.
- `GlassCard`, `CelestiaButton` and the April Fools `ChaosButton` (which had quietly become the de-facto base button) -- every call site ported to `NxButton`'s four roles, the `NxSurface` card family, or `NxColorSurface` for the colour-is-data theme-preview case.
- The 36 MB material-icons-extended dependency goes with the Material Symbols font migration, along with the dangling `composeIcons` catalog entry and leftover material3 `Icon` imports.
- The painter's-algorithm skin leftovers after the scene3d rework (`projectFaces` / `faceAffine` / `frontFacing`, the flat `buildFigure` / `facesToTris` emission).
- The editor weight drag-divider (`SlotDivider`, `LocalSlotDividerDecorator`, `EditModeController.setWidgetWeight`) and its now-dead math test.
- Launching a server from the tray -- the tray is now a window-and-status surface (show window, open console, quit, running-game tooltip) whose every action is an instant in-process operation.
- The dead `IndicationCenter` update-available / mirror-health members (no producer ever wrote or read them).
- The now-unused `SettingsHelpers` rows (`SettingsRowWithDescription` / `SettingsToggleCard` / `ThemeToggleCard` / `HomeViewPicker` / `UiStylePicker` / `VariantPill` / `SettingsSectionTitle` / `SettingsSwitchRow`) and four dead `FontFamily` imports.
- The orphaned `msaNotConfigured` string, dropped when the profile stopped rendering a "not configured in this build" Microsoft dead-end (with no client id the Microsoft category is simply absent).

## [2.3.4] - 2026-06-15

The customization release, consolidated. 2.3.4 makes the entire interface editable -- and editable surfaces now carry their own settings -- rebuilds the Profile around a live 3D render of your skin, ships an in-app update manager with channels and rollback, gives notifications a persistent home both in-app and on the desktop, teaches the UI to fit narrow windows, and bundles the launcher's own type. Underneath: SmartyCraft modpacks join their servers without shipping anything of SmartyCraft's, an installed pack relaunches offline, memory sizes itself, and a deep robustness and refactor pass keeps a corrupt file or a stray widget from taking the launcher down. It rolls up the 2.3.4-beta .. beta5 line and everything since.

### Highlights
- **Make the launcher yours**. Press Ctrl+E to edit. Drag, resize, restyle, and free-place every widget across the home, library, side rails, and the app shell itself, with per-widget glass backing and save / load / export of layout presets. Surfaces now carry their own settings too -- the left rail's selection style lives in its editor panel, not a global menu.
- **Your skin in 3D**. The Profile leads with a live, rotatable 3D render of your skin, drawn from scratch with no extra dependency, and sign-in lives inside the Profile, reachable while logged out.
- **An update manager with channels**. The "i" by the version opens a manager: pick Release / Beta / Alpha (plus Dev / Git source builds), update or roll back to a recent version, and install a desktop shortcut. The About screen also checks on its own every few minutes.
- **Notifications you can keep**. A placeable message-history widget groups repeats, swipes to dismiss, and mutes on do-not-disturb -- and, new this release, the launcher posts a real desktop notification when it slips into the tray so it does not read as a crash.
- **The launcher fits narrow windows**. Rails collapse by a swipe, the server list pages into pills, and the About screen stacks its columns instead of clipping.
- **SmartyCraft modpacks join their servers**. A pack from the mirror that targets a SmartyCraft server connects and joins, and other players' custom skins load -- without shipping anything of SmartyCraft's.
- **Offline relaunch**. An already-installed pack starts with the network off; a warm relaunch makes no network requests at all.
- **Adaptive memory**. A non-pinned instance sizes its heap from your real RAM and refines it over a few sessions; pin a value to opt one out.
- **The launcher's own type**. Google Sans Flex and JetBrains Mono ship inside the app, so the interface looks the same on every machine instead of borrowing the host's fonts.
- **A launcher that does not fall over**. A corrupt world or server file, a widget whose kind left the registry, or a crashed surface no longer takes the whole launcher down.

### Added
- In-app UI editor (Ctrl+E): widget palette with search, drag-and-drop placement, cross-slot moves, slot orientation (Column / Row / Grid), a free-placement Canvas (move / resize / bring-to-front / send-to-back), per-widget backing (glass / corner radius / padding), weight drag-dividers, container and tabs widgets, and layout presets (save / load / export). Home, Library, the side rails, and the app shell are all editable widget surfaces.
- Per-surface settings in the editor: a gear shown only for surfaces that expose settings (currently the left rail) opens a panel mirroring the widget prop panel, so a surface's own options live with the region they configure instead of a global screen. The panel is draggable by its header like the palette, toggles closed on a second tap, and writes through `CustomizationSettings`.
- Configurable left-rail selection style in `CustomizationSettings`: a `NavSelectionStyle` (Pill, Square, Circle, LeftBar, Dot, None), an optional accent color override (6-digit RGB hex), an optional filled<->outlined idle-icon swap, and a `navHoverHighlight` toggle. `NavSlot` is rebuilt off Material's `NavigationRailItem` onto a custom selectable item (the M3 indicator shape is fixed) that draws each decoration behind the icon while keeping tab semantics, ripple, and the April Fools bounce, with outlined icon twins in `NavOutlinedIcons` to avoid the filled/outlined import clash.
- Per-side padding overrides on `WidgetChrome` backing (top/right/bottom/left, `-1` = inherit the uniform value), four sliders under the uniform one in the prop panel; an all-default chrome still normalizes to null and old layouts decode with the sides inheriting the uniform padding.
- Per-instance persisted widget state in a new `widget-state.json` keyed by instanceId, the writable counterpart of editor props: `widget-api` gains `WidgetStateHost` / `LocalWidgetStateHost` / `rememberWidgetState<T>` (a debounced-persisting `MutableState`, default-on-missing/malformed, mirroring `rememberProps`), and `client-ui` implements it with `WidgetStateStore` (versioned envelope, debounced atomic write, per-entry size cap, two-level corruption fallback), `WidgetStateGc` (one collector off the layout-graph flow prunes orphans across editor removal, surface reset, and load migrations) and `WidgetStateFlushHook`. State is keyed off the layout graph so a layout undo cannot revert a user's notes, and the entry follows a moved widget and dies with a removed one.
- A typed reactive data-source layer beside `WidgetServiceRegistry` so widgets bind to data instead of injecting services: `WidgetDataSource<T>` + `SourceKey<T>` in `widget-model` (Compose-free, exposing a `StateFlow` a non-Compose rule engine can read), `WidgetDataRegistry` + `rememberSource(key)` / `current(key)` / `flow(key)` in `widget-api`, and `LocalWidgetDataRegistry` provided at the AppShell root from a Koin singleton that eagerly registers the autosync and notification-archive sources; `ProgressWidget` and the message-history widget read through it rather than injecting `AutoSyncService` / `NotificationArchiveStore`.
- A command/write SPI as the write-side mirror: `WidgetCommand<P>` + `CommandKey<P>` in `widget-model` (Compose-free, fireable headlessly), `WidgetCommandRegistry` + `command` / `suspendCommand` / `rememberCommand` / `rememberAction` and `LocalWidgetCommandRegistry` in `widget-api`; `client-ui` ships a `Commands` catalog (`ClearNotifications`, `CheckUpdate`), and the message-history widget now injects nothing -- it reads via `rememberSource` and clears via `rememberAction`.
- `NotesWidget` (`notes.scratch`) -- a persisted scratchpad via `rememberWidgetState` plus an editor-set title prop -- and a checklist widget, the first widget to carry a `List` in runtime state: add/toggle/delete rows with a monotonic `nextId` for stable Compose keys, title and a hide-completed flag as editor props, the items as runtime state, so two instances keep independent lists across restarts.
- Persistent, placeable message-history widget (`@Widget "notifications.history"`): notifications were ephemeral, so a finished sync or a restart left nothing to review. `NotificationArchiveStore` persists a capped, newest-first log to `<dataDir>/notifications.json` (versioned, atomic via `AtomicFiles`), fed by a serializable `PersistedNotification` that `NotificationCenter` forwards through an injected hook (default no-op keeps the center disk-free and testable), written only on settled non-Progress events so a ~10/sec download tick never thrashes the disk. The widget is one outlined panel of pills -- a bottom-pinned collapsed header (an expand chevron plus a pluralized `<N> messages` count) that opens a bounded, scrollable list (`expandDirection` anchors it up or down) -- folds consecutive identical rows into one count-badged run, swipes a row to drop exactly that run, and slides the whole list off before a trash-pill wipe; timestamps show time only, with 12-hour and stacked-time props.
- OS-level notifications via the new sibling library `dev.hivens:libnotify` (freedesktop D-Bus / WinRT toast / NSUserNotification through Project Panama), consumed by a no-throw `client-ui` `SystemNotifier` mirroring `TrayManager`. `AppShell` posts a first-time "minimized to tray" hint on the `isWindowVisible` true->false transition, gated once by `SettingsData.trayHintShown` and persisted only on a successful post, with a "Show window" action -- so the window vanishing into the tray does not read as a crash.
- Do-not-disturb popup-mute flag on `NotificationCenter` (persisted via `SettingsData`), surfaced as a mute toggle on the history widget: recording and auto-dismiss keep running while muted, so the log still fills and lifting the mute surfaces only what is still live rather than a flood of stale toasts.
- Adaptive narrow-width layout: `Breakpoints` / `WidthClass` give per-container width classes via `BoxWithConstraints` (a widget in a narrow slot adapts on a wide window too), `ServerPill` is the compact capsule alternative to `SquareServerCard`, and `PagedContent` lays fixed-size pages with a prev/next strip; below the relevant class the server list becomes a paginated vertical list of full-width pills (favorites first), the About screen stacks its columns each height-bounded so the credits keep their inner scroll, the right rail collapses at runtime, and the edit-mode pill drops to icon-only chips.
- 3D skin renderer in a new `hivens.ui.skin3d` package. A Compose-free core builds the player as textured boxes (head/body/arms/legs plus the overlay layer, classic vs slim arm width, legacy 64x32) from the standard Minecraft UV layout, rotates by yaw/pitch, projects orthographically, back-face culls, and depth-sorts; `SkinView3D` draws each surviving face with a single Skia `Canvas.drawImageRect` under a per-face affine `Matrix33` at NEAREST sampling, so texels stay sharp with no extra dependency; drag rotates and an idle auto-spin runs while the style engine's motion token is non-zero (Brut holds it still). The geometry, projection, cull and face-affine are unit-tested without a renderer.
- In-app update manager (`UpdateManagerDialog`), opened from the version "i": `UpdateService.listReleases(channel)` fetches the GitHub releases once, classifies and cumulative-filters them by channel, and caches them; `prepareUpdate(version)` resolves a picked version, fetches its `release-manifest.json`, and runs the same OS-asset + manifest-pinned SHA-256 gate as the auto-check, so install and rollback share one verified path (installing an older version is a rollback, no downgrade guard, integrity gate still applies). Release channels `ReleaseChannel { Release, Beta, Alpha, Dev, Git }` replace the `prereleaseChannelEnabled` boolean; a `git describe` of a non-release checkout reads as a source build, and Dev/Git build the launcher from source (`SourceBuildService`, Linux/AppImage) behind the experimental switch and a present toolchain. `DesktopIntegration.installEntry()` writes a `.desktop` entry pointed at the running `$APPIMAGE`.
- SmartyCraft join through a small zero-dependency `-javaagent` that redirects authlib to SmartyCraft at class load (the join, session, and profile calls plus the texture-domain whitelist) and loads other players' unsigned skins; two Settings -> Smarty toggles (network agent on, the older authlib-library swap off as a fallback). The Smarty open-helper swap replaces SmartyCraft's proprietary surveillance mod with an open-source helper, and blocks a launch with no open replacement for the server's game version rather than running the original.
- Offline warm relaunch: an installed pack reuses the version metadata and a matching asset index from disk and relaunches with zero network requests; a missing or changed file still fetches exactly itself. Dependency-aware optional-mod toggling enables the libraries a mod requires and keeps role-sharing mods mutually exclusive.
- Adaptive memory: a profiler `-javaagent` records each session's GC/heap (no mod contact) and the launcher derives the next launch's `-Xmx` between runs, refining a RAM-derived Automatic baseline; metrics flush every 30 seconds so a hard exit does not lose the session, and the live set is read under ZGC and Shenandoah as well as G1 / Parallel / CMS / Serial.
- Bundled type: Google Sans Flex (UI text, variable source sliced to four static weights for predictable Skia rendering) and JetBrains Mono (code/hex/console), both SIL OFL. `NexiraTypography` re-points the Material 3 type scale via `CelestiaTheme`, monospace call sites read a new `LocalMonoFamily` (falling back to the platform monospace outside the theme), and the OFL license texts ship alongside the fonts.
- About screen richer system card backed by a JNA-free `SystemHardware` (`/proc` + `/sys` reads, OSHI/JNA dropped for Panama): CPU shows physical cores / logical threads plus min-max frequency, RAM appends total swap including zram, Display gains DPI and scale, and a Renderer row reports the windowing and Skiko render API; the About logo gains show/hide props for version, build date and tagline, the build date formats with the app locale via `AppStrings.locale`, and the links card wheel-scrolls.
- `DestructiveConfirmDialog` gates Reset Client (a no-undo `deleteRecursively` of `clients/<assetDir>`), Theme/Style and Background reset, and logout behind a confirm step, with a `logout.confirm` puppet hook keeping the two-step flow drivable.
- Russian and German changelogs (`CHANGELOG_RU.md`, `CHANGELOG_DE.md`).

### Changed
- The Profile is decoupled from auth and is the home for identity: `ProfileSurface` takes a nullable `SessionData` and threads `onLogin`/`onLogout`, a Sign-in category renders the relocated `LoginPanel` while signed out (SSL-bypass, capability-driven 2FA, remember-me intact) and re-labels to Security when signed in (the only UI for "Forget saved sign-in"); the Account tab is rebuilt skin-forward (identity panel above the 3D stage), and the right rail's auth panel is gone. The nav entry is ungated and `profile.signin` is `removable=false`.
- Auth extracted from the launcher god-module into `:client-auth` (the provider-agnostic `AuthProvider` SPI + `AbstractCachingAuthProvider`: session cache, retry/error-translation funnel, transient/SSL classifiers) and `:client-auth-smartycraft` (`SmartyCraftAuthProvider`: the `AUTH_SALT`+AES token, V1 login, status mapping, TWOAUTH/2FA flow). `AuthProvider` replaces `IAuthService` and carries `AuthCapabilities(supports2FA)`; SmartyCraft sets it false (its 2FA logs in on the wire but breaks the game-side session) and `LoginPanel` branches on the capability; the new modules carry no Koin and the seam is acyclic.
- Launch/download SPI moves off `java.lang.Process` and untyped values onto Compose-free sealed result types in `hivens.core.launch` (`LaunchState` / `LaunchError` / `PrepareStage` / `LaunchLogEvent`, `LaunchHandle`, `SpawnResult { Started | Failed }`, a `SyncProgress` carrying raw `bytesPerSec`); `LauncherController`'s two launch paths collapse onto one `launchInternal(label, onStart, prepare)` skeleton over a sealed `Prepared`, and the controller binds five read-narrowed client-core interfaces instead of the concrete finals (Koin aliases over the existing singletons). `appModule` splits into intent-named modules (`authModule` / `cacheModule` / `mirrorModule` / `runtimeModule` / `launchPipelineModule` / `updateModule`), and OS/Arch classification folds the divergent `os.name` ladders into one `hivens.core.platform` (`Platform.classify()`, `Arch`, `OS`, `FileManifest.flatten()`).
- Brand, status and border colors route through themed palette tokens instead of raw hex: origin tokens (`originSmartycraft` / `originMirror` / `originModrinth` / `originLocal`) and a decorative ramp on `CelestiaColors` (per dark/light preset, overridable via `ColorRole`) drive the per-origin gradient/avatar and per-name decorative pair from `theme/BrandColors.kt`, status colors map to `error` / `warnAccent` / `success` / `criticalAccent` / `progressAccent`, and `GlassCard` / `CelestiaButton` borders use `colors.outline`. Card / button / chip corners route through the style-wired `MaterialTheme.shapes` (medium = `cardCorner`, small = `buttonCorner`, extraSmall = `buttonCorner/2`) so the Brut flat style flattens the whole component layer in one switch; raw radii stay only where the shape is intrinsic.
- Widget backing rounding describes the widget, not its placement: padding applies as an outer inset before the clip and glass, so the rounded backing hugs the widget's own view and clips its content to the corner (a widget with its own opaque background, like the news rail, kept square corners before), and the corner clip and padding apply outside the glass so both still shape the widget with glass at 0.
- The game console runs fully off the UI thread end to end, so a log flood can no longer block or crash the window. The pack auth-host redirect is gated by pack origin: only SmartyCraft and mirror packs are redirected; Modrinth, local, and own packs keep the default hosts.
- An available launcher update pushes through `NotificationCenter` with an icon and Details/Later actions instead of a bespoke top-right toast, landing in both the live stack and the history log; the modal still owns critical and mandatory updates. `NotificationCard` is driven by the active `StyleSpec` (corner from `cardCorner`, soft shadow under glass, hard border under flat, swipe-to-dismiss) rather than hardcoded geometry.
- The startup update check drops the 12h cooldown and runs each launch (the About auto-check every 5 minutes and the mandatory poll cover it), the running version is injected into `UpdateService` so checks no longer depend on the build tag, and the prerelease-channel toggle is replaced by the manager's channel picker. The news widget gains `maxItems`, a title-search field, isolated scroll, and a `showTitle` prop; home server pills load the per-server `icon.png` and trigger below the Expanded width class.
- Settings polish: the close-after-launch toggle becomes an icon + description row reworded to "after the game starts", the backing slider is renamed "Glass" -> "Glass opacity", the home-view and ui-style pickers wrap in a `FlowRow` of single-line pills, and the "hide to tray after launch" icon uses `MoveToInbox` instead of a bare minus. The About Renderer row reports Xorg (noting XWayland) and the Skia line reads "graphics renderer". The message-history widget seeds into the right rail's new bottom slot by default now that it is stable.
- Dependencies: Kotlin 2.3.21 -> 2.4.0 (compose-compiler / serialization / multiplatform plugins follow; KSP stays 2.3.9), ktor 3.4.3 -> 3.5.0, logback 1.5.34, buildconfig 6.0.10, mockk 1.14.11, JUnit 5.12.2 / platform 1.12.2, Gradle wrapper 9.5.1, and `dev.hivens:libtray` / `libnotify` 0.1.1. JUnit is held on 5.x: 6.x drops Java 8, which `authlib-agent` and `profiler-agent` still target. CI: the AppImage cross-distro portability check is a publish-blocking gate against the freshly built AppImage on Fedora / Arch / Debian-stable, and the `check-comments.py` Style-D scan runs `--strict`.

### Fixed
- `Nbt.read` no longer pre-allocates from an untrusted length. A corrupt `TAG_Byte_Array` / `TAG_Int_Array` / `TAG_Long_Array` / `TAG_List` header with a negative or near-`Int.MAX_VALUE` length called `ByteArray(len)` and threw `OutOfMemoryError`, which is an `Error` not an `Exception` and slipped past the `catch (Exception)` in the world/server scanners, taking the launcher down; the reader rejects a negative length, reads in bounded chunks via `readNBytes`, and grows int/long arrays element-by-element so a bogus length EOFs into a catchable `NbtException`.
- Widgets persisted with a kind that left the registry no longer render nothing while their data lingers: `SlotRenderer` routes an unknown kind through `LocalUnknownWidgetDecorator` (no-op in production), the editor paints an 'unsupported widget' placeholder with one-tap remove, and on a real schema bump `WidgetGraphReconciler` drops instances absent from both the registry and the bundled default, otherwise preserving unknown kinds so a future plugin's kinds survive (#333). Declared container child slots missing from `WidgetInstance.children` are seeded once at startup by the same reconciler so a nested drop stops silently no-opping (#331).
- Preset load runs the saved graph through the migrate-and-merge pipeline rather than writing it verbatim: `LayoutReconcile` is extracted out of `LayoutGraphRepository.load()` and the preset path delegates to it, so an older-schema or older-version preset reconciles exactly like an on-disk load and a duplicate-instanceId preset is refused; preset save now stamps the live schema version (#332). Widget id and instance id uniqueness are enforced at build time (`WidgetRegistryProcessor` fails on a duplicate `@Widget` id) and at load time (the graph falls back to the bundled default on an instanceId collision) (#335).
- `LayoutGraphRepository.load` seeds slots added to a surface that already exists in a saved layout (`mergeMissingSlots` alongside `mergeMissingSurfaces`), so a slot introduced in a later release does not render as a blank pane for any user with a persisted layout; the merge is additive and cannot resurrect a removed widget.
- Unknown persisted enum values fold to an `Unknown` sentinel via a `LenientEnumSerializer` for `PackOrigin` / `PackLoader` / `SlotOrientation` (instead of coercing to the field default and taking the wrong auth redirect / classpath / canvas config), and `SmrtSource` gains the same so a single mod with a future source type no longer aborts the whole `SmrtPackManifest` decode (which killed browse and install for the pack); a forward-incompat write-gate loads a higher-`schema_version` file read-only so an older binary cannot clobber a newer one (#334, #340). `TAG_List` throws on a negative length like the array tags already do.
- `GitHubRelease.name` is nullable: GitHub returns `"name": null` for an untitled release, the non-null field failed to decode, and the exception turned `checkForUpdate` into "no update" for every user until a titled release was published. Source builds (dev / dirty / commits-ahead and the 0.0.0 no-tag fallback) no longer auto-update, so a `[CRITICAL]` release stops forcing an Install/Exit dialog on a developer; the update check costs one `/releases` page per check (shared with the changelog) to protect the unauthenticated rate budget, and a second source build clears the stale `AppDir` before packaging.
- A 2FA login completes against the provider that initiated it (carried in the pending-2FA state) instead of the default `authService`, so a login through the SSL-bypass provider no longer misses its `pendingTwoFactor` cache; `guessModel` bounds the skin sniff to the texture's real size and defaults to Classic so an undersized skin no longer crashes the Profile, and `SkinView3D` frees the previous native Skia `Image` on change/leave.
- Launch flooded a notification group with identical "Syncing <pack>" rows on every progress tick; a live progress run now coalesces (the group head is replaced while both are `Kind.Progress`, a terminal event prepends), and the avatar falls back to a neutral package glyph instead of an empty gray square. The Worlds tab and PackDetail render Loading/Error/Loaded states with retry instead of an endless spinner (#354). The floating palette and prop panel drop their glass for a solid surface so they stop compositing into muddy glass over the rail.
- i18n robustness: `browseDetailInstallProgress` converts off a printf `.format()` string to `$`-interpolation across en/ru/de (a `LocaleFormatParityTest` now fails on any printf specifier in a locale string), count-bearing strings use grammatical plurals (`russianPlural` with the 11-14 carve-out, `twoFormPlural` for en/de), and `ProgressWidget` / `TabContainerWidget` resolve their defaults through `LocalStrings` at render instead of hardcoded Russian literals that showed verbatim on a fresh EN/DE install (#358, #359).
- Dialogs focus on open so the keyboard works without a mouse hop (`ConfirmCodeDialog` caret in the 2FA field, `UpdateDialog` on its primary action, re-requested per state) (#355); a server card dragged-away-before-release no longer keeps a stale focus border (cleared on `PressInteraction.Cancel`); the edit-mode toolbar pill centers over the full window instead of the inset chrome so it stops drifting as the rails collapse and resize; and first-run desktop integration copies the embedded icon into the user hicolor theme and references it by name (`Icon=nexira`) instead of pointing at the AppImage ELF, so environments that ignore an AppImage's embedded icon stop showing a placeholder.
- Adaptive memory reads true host RAM on the installed build (it had fallen back to a fixed 16 GB on the packaged runtime), the experimental master toggle actually disables it at launch, and the transient "settings saved" banner is removed (saves are immediate). The update-manager modal is pinned to a fixed near-opaque panel instead of scaling its alpha by the glass-intensity knob, and `detectToolchain` resolves once.

### Removed
- The 2D paper-doll skin assembly (`assembleSkin`, `getSkinFront` / `getSkinBack`), superseded by the 3D renderer and `SkinManager.getRawSkin`; the bespoke `UpdateNotification` top-right toast, now routed through the notification center; and the right rail's `RightRailAuthPanel` / `AccountPanel`, replaced by sign-in inside the Profile.
- The dead `SkiaTracker` debug instrumentation (the overlay was never mounted but the `track()` calls stayed live and the queue grew unbounded over a session); the migrated-away `SlotAddress` compatibility overloads on `LayoutGraph` and `Modifier.slotBounds`; and two no-op Compose keep-alive functions in `DragAndDrop`.

## [2.3.4-beta5] - 2026-06-09

A profile-and-updates release. The Profile is rebuilt around a live 3D
render of your skin, with sign-in moved inside it and reachable while
logged out. A new in-app update manager adds release channels, rollback,
a desktop-shortcut install, and -- for developers -- building the launcher
from source. Underneath: the launcher no longer dies on a corrupt world
file, auth is carved into its own modules, and the AppImage gains a
cross-distro release gate.

### Highlights
- **Your skin in 3D**. The Profile's account tab leads with a live, rotatable 3D render of your skin, drawn from scratch with no extra dependency.
- **Sign in from the Profile**. The login form lives in the Profile and is reachable while logged out; the cramped right-rail login is gone.
- **An update manager with channels**. The "i" by the version opens a manager: pick a channel (Release / Beta / Alpha, plus Dev / Git source builds), update or roll back to a recent version, and install a desktop shortcut.
- **Background update checks**. The About screen checks for updates on its own every few minutes and tints the running version by its channel.
- **A corrupt world or server file no longer crashes the launcher**. A malformed NBT length used to take the whole launcher down on scan.

### Added
- 3D skin renderer in a new `hivens.ui.skin3d` package. A Compose-free core builds the player as textured boxes (head/body/arms/legs plus the overlay layer, classic vs slim arm width, legacy 64x32) from the standard Minecraft UV layout, rotates by yaw/pitch, projects orthographically, back-face culls, and depth-sorts (painter's). `SkinView3D` draws each surviving face with a single Skia `Canvas.drawImageRect` under a per-face affine `Matrix33` at NEAREST sampling, so texels stay sharp with no extra dependency; drag rotates and an idle auto-spin runs while the style engine's motion token is non-zero (Brut holds it still). The geometry, projection, cull and face-affine are unit-tested without a renderer.
- `SkinManager.getRawSkin(nickname)` returns the raw skin texture (`ImageBitmap`) for the 3D view, reusing the existing disk + bounded-LRU cache and the path-traversal-safe cache filename. The baked 2D front/back paper-doll (`assembleSkin`, `getSkinFront`/`getSkinBack`) is removed.
- In-app update manager (`UpdateManagerDialog`), opened from the version "i" in About. `UpdateService.listReleases(channel)` fetches the GitHub releases once, classifies and cumulative-filters them by channel, and caches them; `prepareUpdate(version)` resolves a picked version from that cache, fetches its `release-manifest.json`, and runs the same OS-asset + manifest-pinned SHA-256 gate as the auto-check (`buildUpdate`), so install and rollback share one verified path. Installing an older version is a rollback -- there is no downgrade guard, and the integrity gate still applies.
- Release channels: `ReleaseChannel { Release, Beta, Alpha, Dev, Git }` in `client-core`, replacing the `prereleaseChannelEnabled` boolean on `SettingsData`. A tag is classified by its prerelease suffix (`-alpha*` -> Alpha, other prerelease -> Beta, none -> Release), and a `git describe` of a non-release checkout (commits-ahead `-<n>-g<sha>` or `-dirty`) reads as a source build (`Dev`). Channel selection is cumulative (Alpha shows alpha + beta + release).
- Build-from-source channels (`SourceBuildService`, Linux/AppImage only). Dev builds the `dev` branch, Git the `stable` branch: it detects the toolchain (git + a JDK + appimagetool), clones/fetches into `<dataDir>/source`, runs `:client-ui:packageReleaseUberJarForCurrentOS` + `:client-ui:emitAppImageProfile` then `scripts/build-appimage.sh`, and hands the produced AppImage to the standard applicator. Gated behind the experimental master switch and a present toolchain; the manager shows what is missing otherwise.
- `DesktopIntegration.installEntry()` writes `~/.local/share/applications/dev.hivens.nexira.desktop` with `Exec`/`Icon` pointed at the running `$APPIMAGE` and nudges `update-desktop-database`. Linux/AppImage only; surfaced as the manager's "Install .desktop entry" action.

### Changed
- The Profile is decoupled from auth and is the home for identity (foundation of the onboarding epic, #365). `ProfileSurface` takes a nullable `SessionData` and threads `onLogin`/`onLogout`: a new Sign-in category renders the relocated `LoginPanel` while signed out (its SSL-bypass, capability-driven 2FA, and remember-me intact) and completes in place; signed in it re-labels to Security and is the only UI for `CredentialsManager.clear()` ("Forget saved sign-in") outside logout, while Account carries the primary logout. The nav entry is ungated. The right rail's auth panel is gone -- `RightRailAuthPanel` and `AccountPanel` are deleted and the auth slot is dropped from `RightPanel` and the default-layout seed. `profile.signin` is `removable=false`.
- The Account tab is rebuilt skin-forward: an identity panel (name + online status, balance + glass top-up, skin upload + refresh) above the 3D skin stage. The standalone Skin tab is disabled (its `profile.skin.section` widget stays registered for a future detailed screen) and the sign-in form is width-constrained.
- Auth extracted from the launcher god-module into `:client-auth` (the provider-agnostic `AuthProvider` SPI + `AbstractCachingAuthProvider`: session cache, retry/error-translation funnel, transient/SSL classifiers) and `:client-auth-smartycraft` (`SmartyCraftAuthProvider`: the `AUTH_SALT`+AES token, V1 login, status mapping, TWOAUTH/2FA flow lifted from the old core `AuthService`). `AuthProvider` replaces `IAuthService` and carries `AuthCapabilities(supports2FA)`; SmartyCraft sets it false (its 2FA logs in on the wire but breaks the game-side session), and `LoginPanel` branches on the capability instead of hardcoding SmartyCraft. `core/AuthService.kt` and `IAuthService.kt` are deleted; the new modules carry no Koin and the seam is acyclic.
- The prerelease-channel Settings toggle is replaced by the channel picker in the update manager; the experimental master switch now gates the Dev/Git source channels. The About update panel auto-checks every 5 minutes while open (stopping once an update is found) and colours the running version by its channel.
- Dependencies: Kotlin 2.3.21 -> 2.4.0 (the compose-compiler, serialization and multiplatform plugins follow the version ref; KSP stays 2.3.9 and compiles clean), ktor 3.4.3 -> 3.5.0, logback 1.5.34, buildconfig 6.0.10, mockk 1.14.11, JUnit 5.12.2 / platform 1.12.2, Gradle wrapper 9.5.1. JUnit is held on the 5.x line: 6.x drops Java 8, which `authlib-agent` and `profiler-agent` still target as bytecode for the legacy 1.12.2 game JVM.
- CI: the AppImage cross-distro portability check (`appimage-portability.yml`) is now a reusable workflow that `build_release.yml` runs as a publish-blocking gate against this run's freshly built AppImage on Fedora / Arch / Debian-stable, instead of a weekly schedule against an already-shipped asset. The `check-comments.py` Style-D scan runs `--strict` as a hard gate (a stray review marker in `DefaultCacheTest` was the last baseline hit), and the linter header no longer points at a private memory path.

### Fixed
- `LayoutGraphRepository.load` now seeds slots added to a surface that already exists in a saved layout, not only whole missing surfaces (`mergeMissingSlots` alongside `mergeMissingSurfaces`). A slot introduced in a later release -- here the new `profile.signin` slot -- otherwise rendered as a blank pane for any user with a persisted layout graph, with no in-product way back. Slots are structural (the editor has no create/delete-slot op), so a missing slot is always an upstream addition and the merge is additive: it cannot resurrect something the user removed.
- `Nbt.read` no longer pre-allocates an array from an untrusted length. A corrupt `TAG_Byte_Array` / `TAG_Int_Array` / `TAG_Long_Array` (or `TAG_List`) header with a negative or near-`Int.MAX_VALUE` length used to call `ByteArray(len)` / `IntArray(len)` / `ArrayList(len)` and throw `OutOfMemoryError`. `OutOfMemoryError` is an `Error`, not an `Exception`, so it slipped past the `catch (Exception)` guard in the world and server NBT scanners and took the launcher down. The reader now rejects a negative length, reads byte arrays in bounded chunks via `readNBytes` (raising `NbtException` on a short stream), and grows the int/long arrays element-by-element so a bogus length EOFs into a catchable `NbtException` instead of reserving gigabytes up front.
- `GitHubRelease.name` is nullable. GitHub returns `"name": null` for a release published without a title; the non-null field failed to decode, and the exception turned `UpdateService.checkForUpdate` into "no update" for every user until a titled release was published. The field now defaults to `null` and the `[CRITICAL]` gate reads it null-safely.
- A second Dev/Git source build aborted: `build-appimage.sh` and `jlink` refuse a pre-existing `AppDir`/output, which the first build leaves behind. `SourceBuildService` now clears the scratch `AppDir` and stale output before packaging.
- The update-manager modal scaled its background alpha by the glass-intensity knob, so a low setting left it near-transparent over the scrimless `BasicAlertDialog`; it is pinned to a fixed near-opaque dark panel. `detectToolchain` ran twice on the composition thread; it is resolved once.

### Removed
- The dead `SkiaTracker` debug instrumentation. Its overlay (`SkiaDebugOverlay`) was never mounted, but the `track()` calls stayed live in `CustomBackground`, `SquareServerCard`, `ServerSettingsScreen` and `ServerDetailsSurface`; only the dead overlay drained the tracking queue, so it grew unbounded over a session (a slow leak -- weak refs free the bitmaps, but the queue wrappers never drain). The four call sites, the `decodeFrame` trackTag plumbing and the `SkiaTracker` file are gone; the diagnostics capability is tracked for a proper mounted, dev-gated replacement.
- The migrated-away `SlotAddress` compatibility overloads: the flat `(SurfaceId, SlotId)` forms of `insertWidget`/`removeWidget`/`reorderInSlot`/`moveWidget` on `LayoutGraph` and `Modifier.slotBounds(SlotAddress)`, transitional shims for the move onto `SlotPath` with no remaining production callers, plus their two compat tests. `SlotAddress` itself stays as the flat leaf identifier behind `EmptySlotDecorator` and the `SlotPath.leafAddress`/`SlotAddress.toPath` bridge.
- Two no-op Compose keep-alive functions (`touchDerivedStateOf`/`touchSnapshot` in `DragAndDrop`): unused private methods the shrinker strips anyway (and the ProGuard config keeps `androidx.compose.**`/`hivens.ui.**` wholesale), so they kept nothing alive; `derivedStateOf` is genuinely used in `ConsoleWindow`.

## [2.3.4-beta4] - 2026-06-07

The SmartyCraft-pack release. A modpack that targets a SmartyCraft
server now connects and joins -- other players' skins included --
without shipping anything of SmartyCraft's. Alongside that: offline
relaunch of an installed pack, dependency-aware optional mods, a console
that no longer freezes under a log flood, and adaptive-memory fixes.

### Highlights
- **SmartyCraft modpacks join their servers**. A pack from the mirror that
  targets a SmartyCraft server now connects and joins, and other players'
  custom skins load.
- **Offline relaunch**. An already-installed pack starts with the network off;
  a warm relaunch makes no network requests at all.
- **Optional mods follow their dependencies**. Enabling an optional mod also
  enables the shared libraries it needs, and switching one mod in an
  interchangeable group (e.g. a recipe viewer) swaps the other out.
- **A console that keeps up**. A heavy mod-load log flood no longer freezes or
  crashes the launcher window.
- **Adaptive memory reads your real RAM**. The installed build now detects the
  host's RAM correctly, and adaptive sizing works under ZGC and Shenandoah.

### Added
- SmartyCraft join through a small agent: for a pack bound to a SmartyCraft
  server, the launcher attaches a zero-dependency `-javaagent` that redirects
  authlib to SmartyCraft at class load (the join, session, and profile calls
  and the texture-domain whitelist), and loads other players' unsigned skins.
  Two Settings -> Smarty toggles: "network agent" (on) and the older
  authlib-library swap (off, kept as a fallback).
- Offline warm relaunch: the version metadata and a matching asset index are
  reused from disk, so an installed pack relaunches with zero network requests;
  a missing or changed file still fetches exactly itself.
- Dependency-aware optional-mod toggling: enabling a mod enables the libraries
  it requires; mods sharing a role stay mutually exclusive.
- Profiler periodic flush: session metrics are written every 30 seconds, so a
  hard exit (a mod's `Runtime.halt`, a native crash) no longer loses the whole
  session.
- Russian and German changelogs (`CHANGELOG_RU.md`, `CHANGELOG_DE.md`),
  starting from this release.

### Changed
- The game console runs fully off the UI thread, end to end, so a log flood can
  no longer block or crash the window.
- Adaptive heap sizing detects the live set under ZGC and Shenandoah, not only
  G1 / Parallel / CMS / Serial.
- The pack auth-host redirect is gated by pack origin: only SmartyCraft and
  mirror packs are redirected; Modrinth, local, and own packs keep the default
  hosts.
- The SmartyCraft skin patch logs one line when a present-but-changed
  `getTextures` can no longer be patched, instead of dropping other players'
  skins silently.

### Fixed
- Adaptive memory reads true host RAM on the installed build (it had fallen
  back to a fixed 16 GB on the packaged runtime, mis-sizing the heap).

## [2.3.4-beta3] - 2026-06-04

Matures the adaptive memory from 2.3.4-beta2 into a three-tier model
(Fixed / Automatic / Adaptive) and cleans up the release notes.

### Highlights
- **Automatic memory baseline**. A non-pinned instance now sizes its heap from
  your machine's RAM (a sane share, capped) instead of a fixed default, so it
  stops over-allocating on a small machine. The adaptive sizer refines this
  baseline over a few sessions.
- **Adaptive governs every instance**. The global Adaptive memory toggle now
  applies to every instance, not just freshly-created ones. Pin a specific RAM
  value to opt one out; turn the toggle off to keep the automatic baseline
  without learning.
- **See and set the mode**. The RAM selector shows an "Auto" chip with the heap
  it currently resolves to, and pack instances get their own Settings tab for RAM.

### Changed
- Adaptive heap derivation is peak-aware: it also covers churn-heavy packs and
  collectors that never report a major GC.

### Fixed
- The experimental master toggle now actually disables adaptive memory at launch
  (previously the launch path ignored it).
- Release notes no longer carry a duplicated "What's Changed" section, and the
  in-app update dialog no longer shows the download table as a changelog.

## [2.3.4-beta2] - 2026-06-03

Adds the experimental adaptive memory sizer on top of 2.3.4-beta.

### Highlights
- **Adaptive memory (experimental)**. New instances measure their real heap use
  while you play and right-size `-Xmx` over the next few launches, so a pack runs
  smoother without hand-tuning RAM. On by default under the experimental settings;
  pick a specific RAM value to opt that instance out.

### Added
- Profiler agent: a small in-JVM measurement agent (GC / heap only, no mod contact)
  that records each session's metrics; the launcher derives the next launch's heap
  from them between runs.

## [2.3.4-beta] - 2026-06-03

The customization release. The launcher's whole interface becomes
editable: an in-app edit mode lets you rearrange, resize, and restyle
every widget -- including the app shell itself -- and save the result as
a preset. The UI also learns to recover: a crash reloads the shell
instead of leaving a dead window. Alongside that: pack browsing and
install from the mirror, a reworked console, multi-loader runtime
support, and Russian / English / German across the interface.

### Highlights
- **Make the launcher yours**. Press Ctrl+E to edit. Drag, resize, and
  rearrange every widget across the home, library, side rails, and the
  app shell itself. A free-placement Canvas mode, per-widget glass
  backing (corner / padding / opacity), and save / load / export of
  layout presets.
- **A UI that recovers instead of dying**. If the interface crashes, the
  launcher reloads its shell on the fly; a repeated crash falls back to a
  minimal quit-only safe screen rather than a frozen window.
- **Browse and install packs from the mirror**. A catalogue page with a
  pack detail view (Content / Files / Worlds), optional-mod toggles, and
  dependency-aware grouping.
- **A calmer console**. Quiet by default, themed to the active palette,
  and available as a file-backed Logs tab on each pack.
- **Smarty servers, without the spyware**. A Settings -> Smarty section
  swaps SmartyCraft's proprietary Smarty mod for an open-source helper:
  same network compatibility, none of the client-side surveillance. If no
  open replacement exists for a server's game version, the launch is
  blocked rather than quietly running the original mod.

### Added
- In-app UI editor (Ctrl+E): widget palette with search, drag-and-drop
  placement, cross-slot moves, slot orientation (Column / Row / Grid),
  free-placement Canvas (move / resize / bring-to-front / send-to-back),
  per-widget backing (glass / corner radius / padding), weight
  drag-dividers, container and tabs widgets, and layout presets
  (save / load / export). Home, Library, the side rails, and the app
  shell are all editable widget surfaces.
- Self-healing UI: the launcher restarts its shell on a composition crash
  and keeps your data; a crash loop opens a standalone safe-mode window.
- Pack browsing and install from the mirror catalogue, with a pack detail
  page (Content / Files / Worlds tabs), optional-mod toggles, rich pack
  metadata, and a per-mod dependency graph with role grouping.
- Multi-loader runtime provisioning and .mrpack install; per-pack managed
  JDK selection.
- Notification center with severity / kind split, accessibility, and
  session controls.
- Cross-cutting cache layer (TTL + stale-while-revalidate, disk-backed).
- Settings -> Smarty section: "Use the alternative smrt network helper"
  and "Exact mod verification" toggles (both on by default).
- Russian, English, and German across the home, library, editor, widgets,
  and notifications, with a CI gate against hardcoded UI strings.
- Keyboard fine-adjustment on sliders (hover, then arrow keys).

### Changed
- Console is quiet by default, themed, and file-backed, and now lives as a
  Logs tab on the pack detail screen.
- Launch failures are delivered as notifications instead of an in-panel
  banner.
- SmartyCraft join re-authenticates before spawning a pack so it sees a
  fresh token.
- libtray is resolved from Maven Central (no longer JitPack).

### Fixed
- Offline relaunch of a server with no cached token no longer crashes the
  client (a missing token had produced a malformed launch argument).
- Unknown values in saved settings are coerced to a default instead of
  wiping the whole settings file.
- Legacy LWJGL2 natives are mirrored through the Mojang CDN first.
- The tray menu seeds from the disk cache before init, so it no longer
  flashes "(No servers)" on startup.
- The window minimum size is clamped to the current screen.

### Removed
- Edit-mode floating action button (replaced by Ctrl+E and Escape).
- Experimental "Hivens Mirror" settings toggle.

## [2.3.3] - 2026-05-25

Visual customization release. Custom background gains real
animated-format support (GIF / APNG / animated WebP) with
playback controls. A new experimental Customization screen
exposes density, glass intensity, accent override, and a full
per-role color override matrix on top of the active theme.

### Highlights
- **Animated wallpapers**. Pick a GIF, APNG, or animated WebP as
  your custom background and it actually animates. Frame 0 shows
  immediately on cold load instead of grey while the remaining
  frames decode. New Animation speed slider (0.25 - 4x, live
  during playback) and Loop mode picker (Use codec / Loop forever
  / Play once -- the last one freezes on the last frame for
  intro-and-settle patterns).
- **Customization (experimental)**. New Settings entry exposes
  density scale (0.85 - 1.15x, all `.dp` values), glass density
  (0 - 100%, every glass surface in the launcher), accent color
  override (free hex), and a full 7-role color override matrix
  behind an experimental toggle.
- **Glass density reaches every glass surface**. Sidebar, right
  panel, dividers, every card and tile across every screen
  respect the slider, not just the few screens it was first
  wired into.
- **GlassCard finally honours palette.glassAlpha**. The Glass
  branch had been hardcoding `alpha = 0.7f` and ignoring the
  palette's own field (0.60 dark, 0.65 light). Now reads the
  palette correctly.

### Added
- Multi-frame background decoder via Skiko `Codec`. Reads
  `frameCount`, `framesInfo[i].duration`, `repetitionCount`.
  Safety cap at 240 frames or ~240 MB raw RGBA -- oversize
  formats fall back to static frame 0 with a logged warning.
- `BackgroundSettings.animationSpeedMultiplier` (0.25 - 4x).
  Slider on BackgroundSettings screen; changes apply mid-playback
  via `rememberUpdatedState`.
- `BackgroundSettings.loopMode` (`UseCodec` / `LoopForever` /
  `PlayOnce`) with segmented control on the same screen.
- GIF and APNG added to the background file picker extension
  filter.
- New `CustomizationExtensionScreen` under Settings ->
  Customization (exp.). Persists separately in
  `customization.json`.
- `CustomizationSettings` data class: `densityScale`,
  `glassIntensity`, `accentOverride`, `colorOverrides` (per-role
  map), `experimentalColorOverridesEnabled` (gates the 7-role
  matrix UI).
- Density scale wired through `LocalDensity` at the app root so
  every `.dp` scales live. The Customization screen counter-wraps
  to base density so the slider stays grabbable while the outer
  UI live-scales as the user drags.
- `glassSurfaceAlpha(baseAlpha)` and `scaledAlpha(color, baseAlpha)`
  helpers in `hivens.ui.customization`. Centralized surface-alpha
  math; migrated 25+ call sites across the codebase.

### Changed
- GlassCard `CardSurface.Glass` reads `palette.glassAlpha *
  customization.glassIntensity` instead of the previously
  hardcoded 0.7. Fixes a long-standing palette-field-ignored bug.
- GlassCard `CardSurface.Flat` (Brut) lerps between
  `palette.glassBackground` and `colorScheme.surface` driven by
  intensity. At intensity = 1.0 the surface stays solid grey
  (Brut identity); lower intensity blends toward translucent
  black so the slider is visually obvious even under the
  "opaque" style.
- AppLayout sidebar, right panel, dividers, plus 22 other
  previously hardcoded `surface.copy(alpha = X)` sites all
  routed through `glassSurfaceAlpha`. Default at intensity = 1.0
  matches the prior hardcoded values byte-for-byte.
- ThemePicker theme cards + preview panel use
  `scaledAlpha(theme.background, 0.8f)` so the theme's own
  background colour scales by glass intensity.

### Fixed
- GlassCard ignored `CelestiaColors.glassAlpha` entirely (used
  hardcoded 0.7f). The two palette presets had the field defined
  but no caller respected it.
- Density slider lost pointer mid-drag. Updating density
  re-measured the slider host on every drag tick; the gesture
  detector lost the pointer. The Customization screen now
  counter-wraps to base density.
- Animated wallpaper cold load showed grey for several seconds
  while all frames decoded. Frame 0 now emits as a preview as
  soon as it lands; the remaining frames decode behind it.

## [2.3.2] - 2026-05-24

Same-day patch on 2.3.1. Three user-facing bugs surfaced within
hours of the 2.3.1 release; this rolls them up.

### Highlights
- **Java 21 downloads work again** for users on CloudFlare WARP and
  similar VPNs. The launcher now adds a real-browser User-Agent on
  the JDK fetch and falls back to Adoptium / GitHub releases when
  BellSoft's CloudFlare CDN refuses the request. Affects launching
  Create and any other 1.21.x pack.
- **"Move data directory" button works** again on every platform.
  The release-build shrinker was culling FileKit internals reached
  only from that one call site; a `keep` rule restores the affected
  overload and a localized error line now surfaces on the rare
  picker failure instead of a silent dead button.
- **April Fools debug panel auto-scrolls into view** when unlocked
  by the 5-tap Diagnostics title gesture. Previously the panel did
  open but rendered below the visible scroll area on most window
  sizes, which read as "the click did nothing but jiggle the list".

### Fixed
- JDK download path silently 403'd from CloudFlare WARP exits
  (CloudFlare bot manager hostile to its own infrastructure when
  the destination is also CloudFlare-fronted, as BellSoft is).
  Added a Chrome-shaped `User-Agent` header on the JDK fetch and a
  mirror list with Adoptium / Temurin GitHub releases as a second
  attempt. GitHub releases are Azure-hosted, not CloudFlare, so the
  WARP-to-CF problem evaporates. Pinned LTS-line Temurin tags for
  each Java major; URL-shape tests catch any rename in the Adoptium
  release path before users do.
- "Move data directory" button in Advanced settings did nothing on
  Win11 and on Linux AppImage release builds. ProGuard's reachability
  analysis had culled the with-`directory` overload of
  `FileKit.openDirectoryPicker` and / or the `PlatformFile(File)`
  constructor as unreachable -- only one call site in the codebase
  used that shape. Added a `-keep` rule covering the whole
  `io.github.vinceglb.filekit` namespace; the picker now opens
  correctly and any future picker failure shows a localized error
  line instead of being silently swallowed.
- April Fools debug panel: 5-tapping the Diagnostics section title
  did toggle the panel state, but DiagnosticsSection has enough
  content below the title that the panel rendered below the visible
  scroll viewport. From the user's view: a small layout shift, no
  visible panel. The panel emit is now wrapped in a
  `BringIntoViewRequester` that scrolls it into view on toggle.
- Existing Roaming-AppData installs from 2.3.0 are now removed
  automatically when 2.3.2 (or 2.3.1) installs over them. The
  `setup.iss` migration hook reads the old install's quiet
  uninstaller and runs it before laying down the new files, so the
  Add / Remove Programs entry no longer stays registered pointing
  at orphaned files. (Codex P1 follow-up on the 2.3.1 install-dir
  move.)

## [2.3.1] - 2026-05-24

Maintenance release focused on the Windows 11 installer bug that
broke OneDrive users, plus a sweep of UI polish on the Style
variant infrastructure that landed in 2.3.0's wake.

### Highlights
- **Windows 11 installer no longer breaks under OneDrive.** New
  installs land in `%LocalAppData%\Nexira\Programs\` instead of the
  OneDrive-synced Roaming tree, so `jvm.dll` stays materialised on
  disk and the "Invalid Image" crash no longer triggers. Existing
  Roaming installs are uninstalled silently when you run this
  installer over them.
- **Style variant infrastructure.** Settings now offers Celestia
  (rounded, glassy, motion-rich) and Brut (sharp, flat, no glow,
  no motion) as live-switchable UI looks. No restart required.
- **Two-column Settings and Profile.** Vertical category navigation
  on the left, selected section's form on the right. Form state
  persists when you switch categories.
- **Portable build ships a README** inside the `Nexira\` folder
  explaining that `Nexira.exe`, `app\`, and `runtime\` must stay
  together. Bilingual (EN / RU). Heads off the very common "copy
  just the EXE to Desktop" mistake.

### Added
- Style variant axis (`SettingsData.uiStyle`): Celestia (default,
  current look) vs Brut (sharp, flat, no glow, no motion). Live
  switches across visible UI through `LocalStyle` and
  `MaterialTheme.shapes`.
- Home view variant axis (`SettingsData.homeView`): Classic
  (current Dashboard) vs LibraryFirst (placeholder for the upcoming
  pack-library IA).
- Placeholder Library, Browse, and PackDetail screens with
  localised "not yet implemented" notices, wired into navigation.
- Experimental Hivens Mirror sync path for the Industrial pack.
  Subject to redesign per the pack-centric architecture roadmap
  (issue #224); do not rely on the current shape.
- `IServerListService` SPI groundwork: the existing SC-bound
  implementation is renamed to `SmartyCraftServerListService` and
  `ServerProfile` gains a `source` field. No user-visible
  difference yet; prepares the second implementation that fetches
  server lists from the Hivens mirror.

### Changed
- Settings screen layout: vertical category navigation on the
  left, selected category's form on the right.
- Profile screen layout mirrors Settings: `SkinSection` and
  `AccountSection` routed through the same category-nav shape.
- `client-ui` module restructure: the Compose entry point split
  into `bootstrap` (pre-Compose pipeline) and `AppShell` (Compose
  root); the pre-existing `utils/` package dissolved into
  `identity/` and `platform/` subpackages. `Main.kt` shrinks from
  822 to 41 LOC.
- Windows installer: install dir moves from `%AppData%\Nexira`
  (Roaming) to `%LocalAppData%\Nexira\Programs` (Local).
  Old-location installs are removed automatically on first run of
  the new installer via the `InitializeSetup` hook.
- Smrt API spec: server entry gains a required `address` field
  (`host:port`); the previous server-id-as-host fallback is
  removed because SC servers do not run on the Minecraft default
  port. Mirror operators must publish `address` for every server.

### Fixed
- **Windows 11 installer** no longer crashes on launch under
  OneDrive's Known Folder Move (`STATUS_CLOUD_FILE_NOT_IN_SYNC`,
  surfaced as "Nexira.exe -- Invalid Image"). Closes #225.
- **Portable launcher** ships a bilingual README explaining the
  keep-files-together constraint. Closes #227.
- Bootstrap logger initialisation order: the class-level logger
  field in `LauncherBootstrap` instantiated during the object's
  static init, before the body could set `nexira.logs.dir`.
  Logback then pinned the file appender to the fallback path and
  the CI smoke test never saw `launcher.log`. The field was
  unused; removed.
- Skin avatar visibility and outer frame transparency under the
  Brut style variant: Settings, Profile, ServerDetail, and
  ServerSettings frames stay glassy when Brut is active.
- April Fools chaos buttons no longer leak the Material 3 default
  state layer or the elevation hover shadow.
- Mirror sync robustness: streams downloads via
  `prepareGet { execute }` to keep peak memory bounded regardless
  of file size; prunes orphan jars after sync; wipes the mods
  directory on a source flip between SmartyCraft and the mirror.
- Disabled-mod cleanup runs on every sync, including when the
  manifest cache short-circuits the integrity walk. Previously
  the cleanup pass would skip when the cache was hot, leaving
  reintroduced stale jars on disk.
- Linux app icon now forces RGBA output so wlroots and Mutter
  compositors render it correctly in the task bar.
- JDK validation: existing and freshly-downloaded JDKs are probed
  for runnability before being marked as the active runtime; the
  download path also waits for the child process to terminate
  before the resolver returns.

### Removed
- Stale `packageWindowsPortableZip` Gradle task. It pointed at the
  pre-`customJpackageImage` Compose Desktop output path and
  produced empty / broken zips. CI's PowerShell step in
  `build_release.yml` has been the real portable builder for some
  time; a comment in the same place now points future contributors
  at the CI step.

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
