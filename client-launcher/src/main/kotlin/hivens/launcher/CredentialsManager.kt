package hivens.launcher

import com.google.gson.Gson
import hivens.core.util.SecurityUtils
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path

class CredentialsManager(dataDir: Path, private val gson: Gson) {
    private val log = LoggerFactory.getLogger(CredentialsManager::class.java)
    private val dataFile: Path = dataDir.resolve("credentials.dat")

    fun save(username: String, password: String) {
        try {
            val encryptedPass = SecurityUtils.encrypt(password)
            val data = Credentials(username, encryptedPass)
            Files.writeString(dataFile, gson.toJson(data))
        } catch (e: IOException) {
            log.error("Failed to save credentials", e)
        }
    }

    fun load(): Credentials? {
        if (!Files.exists(dataFile)) return null
        return try {
            val json = Files.readString(dataFile)
            val data = gson.fromJson(json, Credentials::class.java)
            if (data?.encryptedPassword != null) {
                data.decryptedPassword = SecurityUtils.decrypt(data.encryptedPassword)
                return data
            }
            null
        } catch (e: Exception) {
            log.error("Failed to load credentials", e)
            null
        }
    }

    fun clear() {
        try {
            Files.deleteIfExists(dataFile)
        } catch (e: IOException) {
            log.error("Failed to clear credentials", e)
        }
    }

    data class Credentials(
        val username: String = "",
        val encryptedPassword: String? = null
    ) {
        @Transient
        var decryptedPassword: String? = null
    }
}
