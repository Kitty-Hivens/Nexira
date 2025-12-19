package hivens.core.data

import java.io.File

data class SettingsData(
    var javaPath: String? = null,
    var memoryMB: Int = 4096,
    var theme: String = "Warm", // TODO: Устарело.
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
            val os = System.getProperty("os.name").lowercase()
            val javaHome = System.getProperty("java.home")
            val javaBin = javaHome + File.separator + "bin" + File.separator + if (os.contains("win")) "java.exe" else "java"

            data.javaPath = javaBin
            data.memoryMB = 4096
            data.theme = "Warm"
            data.saveCredentials = true
            return data
        }
    }
}
