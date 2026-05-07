package hivens.launcher

import hivens.config.Storage
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
    private val fileName = Storage.PROFILES_FILE

    // Profile storage
    private val profiles = ConcurrentHashMap<String, InstanceProfile>()

    // Favorites Vault
    // We use synchronizedSet or simply HashSet with synchronization when writing,
    // but for ease of reading in the UI, we’ll make a copy during get.
    private val _favorites = ConcurrentHashMap.newKeySet<String>()

    var lastServerId: String? = null

    // Public access to favorites (for UI)
    val favoriteServers: Set<String>
        get() = _favorites.toSet()

    @Serializable
    private data class ProfilesContainer(
        val lastServerId: String? = null,
        val profiles: Map<String, InstanceProfile> = emptyMap(),
        val favorites: Set<String> = emptySet()
    )

    init {
        load()
    }

    /**
     * Toggles the favorite status for the server.
     */
    fun toggleFavorite(assetDir: String) {
        if (_favorites.contains(assetDir)) {
            _favorites.remove(assetDir)
        } else {
            _favorites.add(assetDir)
        }
        save()
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
                // Old format support (migration)
                try {
                    val map = json.decodeFromString<Map<String, InstanceProfile>>(text)
                    ProfilesContainer(null, map)
                } catch (e2: Exception) {
                    log.error("Unknown profiles format", e2)
                    return
                }
            }

            container.profiles.forEach { (k, v) -> profiles[k] = v }

            // Loading favorites
            _favorites.clear()
            _favorites.addAll(container.favorites)

            this.lastServerId = container.lastServerId

            log.info("Loaded ${profiles.size} profiles and ${_favorites.size} favorites.")
        } catch (e: IOException) {
            log.error("Failed to load profiles", e)
        }
    }

    fun save() {
        val file = workDir.resolve(fileName)
        try {
            val container = ProfilesContainer(
                lastServerId = lastServerId,
                profiles = profiles.toMap(),
                favorites = _favorites.toSet()
            )
            val text = json.encodeToString(container)
            Files.writeString(file, text)
        } catch (e: IOException) {
            log.error("Failed to save profiles!", e)
        }
    }
}
