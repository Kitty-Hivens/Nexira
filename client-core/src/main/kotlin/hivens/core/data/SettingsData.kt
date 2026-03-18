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
    var startInTray: Boolean = false
)
