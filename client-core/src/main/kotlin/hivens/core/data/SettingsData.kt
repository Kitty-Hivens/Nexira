package hivens.core.data

import kotlinx.serialization.Serializable

/**
 * Which Home surface the user is currently running. Set in Settings.
 * Lets the user A/B between the legacy Dashboard (SC server grid +
 * launch panel + always-on right panel) and the new Library-first IA
 * (Library + Browse + sliding login panel + unified card). Until the
 * Library surface lands properly, [LibraryFirst] routes to the
 * "not yet implemented" placeholder -- intentional: the toggle is
 * how the user explores the direction before it's finished.
 */
@Serializable
enum class HomeView {
    Classic,
    LibraryFirst,
    // [New] -- widget-composed prototype home (Phase 1 / kernel-3). Carries
    // the minimal welcome / recent-packs / quick-launch widgets; the
    // expressive build-out happens as user customization in later phases.
    New,
}

/**
 * Which visual style variant is active. Independent from palette
 * (themes live in `ThemeManager.CustomTheme`). Style governs form,
 * surface treatment, motion -- not color. See `hivens.ui.theme.StyleSpec`
 * for the token set and the two initial variants.
 *
 * - [Celestia] -- rounded corners, glass cards, soft glow, animations.
 *   Current default; matches the launcher's pre-Atelier feel.
 * - [Brut] -- hard corners, flat surfaces, no glow, no animations.
 *   Designed for the user's "жёсткий интерфейс" personal lean as one
 *   open direction under Atelier exploration.
 */
@Serializable
enum class UiStyle { Celestia, Brut }

/**
 * Which source drives the dark/light choice. Exactly one is active:
 *
 * - [Manual] -- the user's own day/night toggle; [SettingsData.isDarkTheme] as set.
 * - [System] -- follow the OS colour scheme (XDG portal / registry / defaults).
 * - [Wallpaper] -- follow the wallpaper's average brightness.
 *
 * Flipping the day/night toggle while in [System]/[Wallpaper] drops back to
 * [Manual] -- an explicit choice always wins over an automatic source.
 */
@Serializable
enum class ThemeMode { Manual, System, Wallpaper }

/**
 * The theme mode a fresh session starts in. Migrates the pre-mode opt-in: a
 * settings file that enabled [SettingsData.themeFromWallpaper] before
 * [SettingsData.themeMode] existed decodes with the field's default ([ThemeMode.System]),
 * so the legacy flag promotes exactly that default to [ThemeMode.Wallpaper] -- a
 * non-default stored mode is an explicit choice and wins over the flag.
 */
fun resolveInitialThemeMode(s: SettingsData): ThemeMode =
    if (s.themeMode == ThemeMode.System && s.themeFromWallpaper) ThemeMode.Wallpaper else s.themeMode

@Serializable
data class SettingsData(
    val javaPath: String? = null,
    /**
     * Fallback default heap (MB) when an InstanceProfile doesn't
     * specify its own. 6 GB matches modded-MC reality (SmartyCraft
     * packs need 4-6 GB to be smooth); RamSelector caps the choice at
     * 75% of detected system RAM so low-RAM systems still scale down.
     */
    val memoryMB: Int = 6144,
    val isDarkTheme: Boolean = true,
    /**
     * Derive the colour palette from the wallpaper (Material You / Monet): the
     * dominant colour of the background seeds tinted tonal surfaces, so planes
     * differ by colour, not just lightness. On by default. Off -> the fixed
     * Celestia palette (and manual theme overrides) apply as before.
     */
    val paletteFromWallpaper: Boolean = true,
    /**
     * Legacy mirror of `themeMode == Wallpaper`, kept so a downgrade to a build
     * that predates [themeMode] still honours the wallpaper opt-in. New code
     * reads [themeMode] and only writes this field in step with it.
     */
    val themeFromWallpaper: Boolean = false,
    /**
     * Which source drives dark/light -- see [ThemeMode]. [isDarkTheme] stays the
     * resolved value the automatic sources write through, so everything downstream
     * (and older builds) keeps reading one boolean. Defaults to following the OS:
     * where the scheme is unreadable the automatic source just never fires, so a
     * fresh install falls back to [isDarkTheme]'s own default.
     */
    val themeMode: ThemeMode = ThemeMode.System,
    /**
     * Replace the OS title bar with the in-app top bar (undecorated window +
     * custom caption buttons / drag / resize). On by default. Escape hatch: if a
     * window manager -- or a future native-Wayland JVM, where client-side window
     * control differs from today's XWayland path -- misbehaves with the custom
     * chrome, turning this off restores the OS-decorated window. Applies at the
     * next launch, since `undecorated` is fixed when the window is created.
     */
    val useCustomChrome: Boolean = true,
    /**
     * Hide launcher window to tray after Play. Off by default -- most
     * users want the launcher visible after launch (switch servers,
     * open console). Opt-in for users who want out-of-sight behavior.
     */
    val closeAfterStart: Boolean = false,
    val saveCredentials: Boolean = true,
    val savedFileManifest: FileManifest? = null,
    /** BCP-47 language tag: "ru", "en", "de". */
    val locale: String = "en",
    /** Offline mode: skip authentication, play with an offline identity. */
    val isOfflineMode: Boolean = false,
    /**
     * The offline-play name chosen via "Play offline". Drives the offline UUID
     * (vanilla OfflinePlayer:<name>) and lets a restart restore the offline
     * identity without re-typing. Null = none chosen yet; auto-login then falls
     * back to the last signed-in name.
     */
    val offlinePlayerName: String? = null,

    // ── Experimental features ────────────────────────────────────────────
    // Master gates both children -- switching off disables the sub-toggles
    // regardless of their stored values. Defaults ON because the upstream
    // protocol is currently a moving target and users need to receive
    // emergency updates promptly.

    /** Master switch for the entire experimental features section. */
    val experimentalFeaturesEnabled: Boolean = true,

    /** Block startup when installed version < `mandatory_min_version` from `meta/update-channel.json`. */
    val mandatoryUpdatesEnabled: Boolean = true,

    /**
     * Update channel the user follows (Release / Beta / Alpha / Dev / Git).
     * Release/Beta/Alpha pick a GitHub release; Dev/Git build from source and
     * are only reachable when [experimentalFeaturesEnabled] is on. Defaults to
     * [ReleaseChannel.Release]; the user picks a channel in the update manager.
     */
    val updateChannel: ReleaseChannel = ReleaseChannel.Release,

    /**
     * Sync all installed server packs in background on startup.
     * "Installed" means a non-empty `clients/<server>/` directory --
     * never triggers a many-GB first-time pack download out of nowhere.
     * Sequential to avoid bandwidth contention; ManifestCache makes the
     * common nothing-changed case complete in milliseconds.
     *
     * Off by default: most users play 1-2 servers; this is maintainer-
     * grade convenience for users with many servers installed.
     */
    val autoSyncAllPacks: Boolean = false,

    /**
     * Reveals the visual JVM-args builder in the per-server constructor.
     * `InstanceProfile.jvmArgs` is free-text, which requires knowing
     * what `-XX:+UseG1GC -XX:MaxGCPauseMillis=200` means;
     * `JvmArgsBuilderDialog` presents a curated preset picker + GC tabs +
     * per-knob explanations, output still writes back to
     * `InstanceProfile.jvmArgs` unchanged. Off by default -- power-user
     * surface; the free-text field is enough for users who know what
     * they want.
     */
    val jvmBuilderEnabled: Boolean = false,

    /**
     * Adaptive memory: let the profiler agent size each instance's heap from its
     * observed live-set + peak instead of the static per-instance heap. On by
     * default (under the experimental master) -- the master switch that governs
     * EVERY instance. An instance opts out only by pinning a specific RAM value
     * (`fixedMemory` on `InstanceProfile` / `InstanceRuntime`); turning this off
     * forces every instance back to its static heap.
     */
    val adaptiveMemoryEnabled: Boolean = true,

    /**
     * Skip the direct-channel attempt; route every SmartyCraft request
     * through SOCKS5 from the first call. Off by default -- direct works
     * for ~99% of users. Enable when `smartycraft.ru:443` is blocked
     * (censored regions, corporate firewalls) but
     * `proxy.smartycraft.ru:58613` gets through. Persisted so users in
     * those networks set this once and forget.
     */
    val forceProxyMode: Boolean = false,

    /**
     * Override for the version string sent in the dashboard handshake,
     * the User-Agent header, and `-Dminecraft.launcher.version`. Null /
     * blank uses `Protocol.DEFAULT_MIMIC_LAUNCHER_VERSION`. Persisted
     * (rather than relying on the `-Dsmrt.mimic.version` CLI flag) so
     * the override survives launcher restart; aimed at reacting to an
     * upstream version pin faster than the Nexira release cycle.
     */
    val mimicVersionOverride: String? = null,

    /**
     * Which Home surface to render after login. Lets the user explore
     * the new Library-first IA without committing the whole launcher
     * to it -- the toggle flips back at any time. See [HomeView] for
     * the option set and [[project_home_library_ia]] for the IA spec
     * the LibraryFirst variant is reaching toward.
     */
    val homeView: HomeView = HomeView.Classic,

    /**
     * Visual style variant. Independent from palette / color preset.
     * Lets the user compare form/surface/motion approaches concretely
     * rather than guessing in the abstract. Defaults to Celestia
     * (current visual feel); see [UiStyle] for available variants.
     */
    val uiStyle: UiStyle = UiStyle.Celestia,

    /**
     * "Do not disturb": mute the live top-right notification popups. Events are
     * still recorded to the history log (the notification-history widget keeps
     * filling) and still auto-dismiss; only the toast rendering is suppressed.
     * Off by default; toggled from the history widget's mute button.
     */
    val doNotDisturb: Boolean = false,

    // ── Smarty server controls ───────────────────────────────────────────

    /**
     * Swap the upstream Smarty surveillance coremod for our open-smrt-network
     * helper when syncing a raw SmartyCraft server. The mirror packs already
     * carry the replacement in their manifest; this brings the same swap to
     * servers synced straight from SC. The replacement jar is resolved per
     * MC version from open-smrt-network's GitHub releases. Authoritative: the
     * Smarty jar is always stripped, never re-admitted. If no replacement is
     * available for the server's MC version (and none is cached on disk), the
     * launch is BLOCKED rather than running the surveillance mod.
     */
    val useOpenSmrtHelper: Boolean = true,

    /**
     * After sync, delete every jar in `mods/` that the server manifest does not
     * list (the injected open-smrt helper aside). The blunt, exact version of
     * "only what the server asks for runs". Removes user-added client mods
     * too -- that is the point, and the Settings copy says so.
     */
    val strictModVerification: Boolean = true,

    /**
     * Attach the network-support `-javaagent` when launching an SC-bound pack.
     * The agent redirects the game's authlib endpoints to SmartyCraft at
     * class-load -- the in-game join and the skin/texture whitelist -- so the
     * join authenticates against SC and skins still load, WITHOUT shipping or
     * swapping SC's patched authlib jar. On by default: this is how an SC-bound
     * pack reaches the server. No effect on non-SC packs. Turning it off while
     * [useSmartycraftAuthLib] is also off leaves an SC join with the vanilla
     * authlib, which the server rejects.
     */
    val useNetworkAgent: Boolean = true,

    /**
     * Source SmartyCraft's own patched `authlib` from the SC client distribution
     * and swap it onto the pack's classpath instead of the vanilla one. The older
     * mechanism, superseded by [useNetworkAgent] and kept as an opt-in fallback;
     * off by default. No effect on non-SC packs. When on, the patched jar is
     * mandatory -- the launch is blocked if it cannot be sourced, since vanilla
     * authlib is a guaranteed rejection.
     */
    val useSmartycraftAuthLib: Boolean = false,

    // ── Onboarding state (not a user-facing toggle) ──────────────────────

    /**
     * True once the one-time "still running in the tray" OS notification has
     * been shown. The first time the launcher hides its window to the tray we
     * post a system notification (visible while the window is gone) so the user
     * doesn't think the app vanished; this flag suppresses it on every
     * subsequent hide. Internal onboarding state -- no Settings UI surfaces it.
     */
    val trayHintShown: Boolean = false,

    /**
     * Which signed-in account fronts the shell -- the provider id of the chosen
     * "face", or null for automatic licence-priority (the Microsoft account
     * before SmartyCraft). With several accounts active at once, this pins whose
     * name and skin the shell shows; the launch still routes per content.
     */
    val preferredFaceProvider: String? = null,

    // -- Boot recovery state (not a user-facing toggle) -------------------

    /**
     * Modules boot recovery has disabled -- [ModuleId] ids the launcher skips at
     * startup (tray, notify, skinema, keyring). NOT a normal settings toggle:
     * written only from the recovery surface, effective on the next boot. Stored
     * as stable string ids so an id a build does not recognise is ignored rather
     * than resetting the file. Empty = everything on.
     */
    val disabledModules: Set<String> = emptySet(),
)
