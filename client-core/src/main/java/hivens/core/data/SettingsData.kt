package hivens.core.data

data class SettingsData(
    var javaPath: String? = null,
    var memoryMB: Int = 4096,
    var isDarkTheme: Boolean = true,
    var seasonalTheme: SeasonTheme = SeasonTheme.AUTO,
    var closeAfterStart: Boolean = true,
    var saveCredentials: Boolean = true,
    var savedUsername: String? = null,
    var savedUuid: String? = null,
    var savedAccessToken: String? = null,
    var savedFileManifest: FileManifest? = null
) {
    companion object {
        fun defaults(): SettingsData {
            val data = SettingsData()
            data.javaPath = null
            data.memoryMB = 4096
            data.isDarkTheme = true
            data.saveCredentials = true
            return data
        }
    }
}
