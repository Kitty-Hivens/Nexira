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
     * Hide launcher window to tray after Play. Off by default -- most
     * users want the launcher visible after launch (switch servers,
     * open console). Opt-in for users who want out-of-sight behavior.
     */
    val closeAfterStart: Boolean = false,
    val saveCredentials: Boolean = true,
    val savedFileManifest: FileManifest? = null,
    /** BCP-47 language tag: "ru", "en", "de". */
    val locale: String = "en",
    /** Offline mode: skip authentication, use cached session. */
    val isOfflineMode: Boolean = false,

    // ── Experimental features ────────────────────────────────────────────
    // Master gates both children -- switching off disables the sub-toggles
    // regardless of their stored values. Defaults ON because the upstream
    // protocol is currently a moving target and users need to receive
    // emergency updates promptly.

    /** Master switch for the entire experimental features section. */
    val experimentalFeaturesEnabled: Boolean = true,

    /** Block startup when installed version < `mandatory_min_version` from `meta/update-channel.json`. */
    val mandatoryUpdatesEnabled: Boolean = true,

    /** Include GitHub prereleases (RC / beta) when picking the update target. */
    val prereleaseChannelEnabled: Boolean = true,

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
)
