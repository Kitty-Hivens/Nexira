package hivens.core.data

import kotlinx.serialization.Serializable

@Serializable
data class SettingsData(
    var javaPath: String? = null,
    var memoryMB: Int = 4096,
    var isDarkTheme: Boolean = true,
    var closeAfterStart: Boolean = true,
    var saveCredentials: Boolean = true,
    var savedFileManifest: FileManifest? = null,
    /** BCP-47 language tag: "ru", "en", "de" */
    var locale: String = "en",
    /** Offline Mode: skip authentication, use cached session */
    var isOfflineMode: Boolean = false,
    /** Start minimized to tray; hide window on close instead of exiting */
    var startInTray: Boolean = false,

    // ── Experimental features ─────────────────────────────────────────────────
    // Three knobs that opt the user into faster-but-less-stable update behaviour.
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
    var prereleaseChannelEnabled: Boolean = true
)
