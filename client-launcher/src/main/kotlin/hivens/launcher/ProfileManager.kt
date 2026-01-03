package hivens.launcher

import hivens.config.AppConfig
import hivens.core.data.InstanceProfile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

class ProfileManager(
    private val workDir: Path,
    private val json: Json
) {
    private val log = LoggerFactory.getLogger(ProfileManager::class.java)
    private val fileName = AppConfig.FILES_PROFILES
    private val profiles = ConcurrentHashMap<String, InstanceProfile>()
    var lastServerId: String? = null

    @Serializable
    private data class ProfilesContainer(
        val lastServerId: String? = null,
        val profiles: Map<String, InstanceProfile> = emptyMap()
    )

    init {
        load()
    }

    fun getProfile(serverId: String): InstanceProfile {
        return profiles.computeIfAbsent(serverId) { InstanceProfile(it) }
    }

    fun saveProfile(profile: InstanceProfile) {
        profiles[profile.serverId] = profile
        save()
    }

    private fun load() {
        val file = workDir.resolve(fileName)
        if (!Files.exists(file)) return

        try {
            val text = Files.readString(file)
            val container = try {
                json.decodeFromString<ProfilesContainer>(text)
            } catch (_: Exception) {
                try {
                    val map = json.decodeFromString<Map<String, InstanceProfile>>(text)
                    ProfilesContainer(null, map)
                } catch (e2: Exception) {
                    log.error("Unknown profiles format", e2)
                    return
                }
            }

            container.profiles.forEach { (k, v) -> profiles[k] = v }
            this.lastServerId = container.lastServerId

            log.info("Loaded ${profiles.size} profiles.")
        } catch (e: IOException) {
            log.error("Failed to load profiles", e)
        }
    }

    fun save() {
        val file = workDir.resolve(fileName)
        try {
            val container = ProfilesContainer(lastServerId, profiles.toMap())
            val text = json.encodeToString(container)
            Files.writeString(file, text)
        } catch (e: IOException) {
            log.error("Failed to save profiles!", e)
        }
    }
}
