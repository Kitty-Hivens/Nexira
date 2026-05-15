package hivens.core.data

import kotlinx.serialization.Serializable

@Serializable
data class SettingsData(
    var javaPath: String? = null,
    /**
     * Default heap size in MB used as a fallback when an InstanceProfile
     * doesn't specify its own. 6 GB is the saner-for-modded-MC baseline:
     * SmartyCraft packs (50-70 mods) need 4-6 GB to run smoothly, and
     * vanilla can always be lowered per-server. RamSelector caps choices
     * at 75% of detected system RAM, so this default also gracefully
     * scales down on low-RAM systems.
     */
    var memoryMB: Int = 6144,
    var isDarkTheme: Boolean = true,
    /** Hide launcher window to tray after the user clicks Play. Off by
     *  default — most users want the launcher to stay visible after
     *  launching the game (it's where they go back to switch servers,
     *  open console, etc). Opt-in for users who specifically want the
     *  out-of-sight behaviour. */
    var closeAfterStart: Boolean = false,
    var saveCredentials: Boolean = true,
    var savedFileManifest: FileManifest? = null,
    /** BCP-47 language tag: "ru", "en", "de" */
    var locale: String = "en",
    /** Offline Mode: skip authentication, use cached session */
    var isOfflineMode: Boolean = false,

    // ── Experimental features ─────────────────────────────────────────────────
    // Three knobs that opt the user into faster-but-less-stable update behavior.
    // The master toggle gates both children — switching it off disables the
    // sub-toggles regardless of their stored values. Defaults are ON because
    // the upstream protocol is currently a moving target and we need users to
    // receive emergency updates promptly. Once the protocol stabilises the
    // mandatory default should drop to OFF.
    /** Master switch for the entire "Experimental features" settings section. */
    var experimentalFeaturesEnabled: Boolean = true,
    /** Block startup when installed < `mandatory_min_version` from `meta/update-channel.json`. */
    var mandatoryUpdatesEnabled: Boolean = true,
    /** Include GitHub prereleases (RC/beta) when picking the update target. */
    var prereleaseChannelEnabled: Boolean = true,
    /**
     * Sync all installed server packs in the background on launcher startup.
     *
     * "Installed" means there's a non-empty `clients/<server>/` directory —
     * we never trigger a many-GB first-time pack download out of nowhere.
     * Sequential sync (one server at a time) to avoid bandwidth contention
     * and let the per-server status badges read clearly. ManifestCache (2.2.9)
     * makes the common case cheap: when nothing changed upstream, the integrity
     * walk short-circuits and the sync completes in milliseconds.
     *
     * Off by default because it costs bandwidth and most users only play 1-2
     * servers; primarily a maintainer-grade convenience for users with many
     * servers installed who want fresh state without clicking each one.
     */
    var autoSyncAllPacks: Boolean = false,

    /**
     * Reveals the visual JVM-args builder in the per-server constructor.
     *
     * The default jvm-args field in [hivens.core.data.InstanceProfile] is
     * a free-text string — to hand-craft Aikar's flags or pick a GC the
     * user has to know what `-XX:+UseG1GC -XX:MaxGCPauseMillis=200` means.
     * The builder dialog (`JvmArgsBuilderDialog`) presents a curated
     * preset picker, GC tabs, and per-knob explanations; the resulting
     * args still write to InstanceProfile.jvmArgs unchanged.
     *
     * Off by default because the feature is power-user-grade and the free-
     * text field is enough for users who already know what they want.
     */
    var jvmBuilderEnabled: Boolean = false,

    /**
     * Conduit Phase 2: skip the direct-channel attempt and route every
     * SmartyCraft request through the SOCKS5 proxy from the first call.
     *
     * Default false — direct works for ~99% of users (`reference_smartycraft_proxy`).
     * Enable when in censored regions or corporate firewalls where
     * `smartycraft.ru:443` is blocked but `proxy.smartycraft.ru:58613`
     * (despite the unusual port) gets through.
     *
     * Persisted here so the toggle survives launcher restart — users in
     * those networks need to set this once, not every session.
     */
    var forceProxyMode: Boolean = false,
)
