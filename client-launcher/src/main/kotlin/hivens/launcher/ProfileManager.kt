package hivens.launcher

import hivens.config.Storage
import hivens.core.data.InstanceProfile
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

class ProfileManager(
    private val workDir: Path,
    private val json: Json
) {
    private val log = LoggerFactory.getLogger(ProfileManager::class.java)
    private val fileName = Storage.PROFILES_FILE

    // Profile storage
    private val profiles = ConcurrentHashMap<String, InstanceProfile>()

    // Favorites set. ConcurrentHashMap.newKeySet gives thread-safe add/remove
    // but `contains-then-add/remove` is still a TOCTOU race -- use the
    // `add()`/`remove()` boolean returns in toggleFavorite() to make the flip
    // atomic instead.
    private val _favorites = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    var lastServerId: String? = null

    // Public access to favorites (for UI)
    val favoriteServers: Set<String>
        get() = _favorites.toSet()

    /** Serializes save() so two concurrent writers can't produce a torn file. */
    private val writeLock = Any()

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
     * Atomically flips favorite state for [assetDir]. The previous
     * `if (contains) remove else add` shape was a TOCTOU race -- two parallel
     * toggles on the same server could both observe contains=true and both
     * call remove(), losing the user's second click. `Set.add` and
     * `Set.remove` on ConcurrentHashMap.newKeySet return true iff they
     * actually changed the set; we use those returns so a concurrent toggle
     * pair always nets exactly one state flip.
     *
     * The mutation happens under [writeLock] so a concurrent save() can't
     * iterate the keyset while another toggle is mid-add (CHM's iterator is
     * weakly consistent but `_favorites.toSet()` can still throw
     * NoSuchElementException if the size estimate races a removal).
     */
    fun toggleFavorite(assetDir: String) {
        synchronized(writeLock) {
            if (!_favorites.add(assetDir)) _favorites.remove(assetDir)
        }
        save()
    }

    fun getProfile(serverId: String): InstanceProfile {
        // Never-configured servers fall under the global adaptive sizer like every
        // other instance; an explicit RAM pick in ServerSettingsScreen sets
        // fixedMemory to opt back out.
        return profiles.computeIfAbsent(serverId) { InstanceProfile(it) }
    }

    fun saveProfile(profile: InstanceProfile) {
        synchronized(writeLock) { profiles[profile.serverId] = profile }
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

    /**
     * Persist the current snapshot. Writes go through a temp file + atomic
     * rename so a crash mid-serialize never leaves a half-JSON profiles.json
     * on disk (the next boot would otherwise reset every per-server tweak
     * the user made -- they describe this as "settings reset themselves").
     * Serialised by [writeLock] so two concurrent saves don't race the
     * temp-file -> final rename.
     */
    fun save(): Unit = synchronized(writeLock) {
        val file = workDir.resolve(fileName)
        val tmp = workDir.resolve("$fileName.tmp")
        try {
            val container = ProfilesContainer(
                lastServerId = lastServerId,
                profiles = profiles.toMap(),
                favorites = _favorites.toSet()
            )
            val text = json.encodeToString(container)
            Files.writeString(tmp, text)
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                // Some Windows-style filesystems (FAT32 over USB) don't support
                // ATOMIC_MOVE across filename changes; fall back to plain
                // replace which is still better than the prior writeString.
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: IOException) {
            log.error("Failed to save profiles!", e)
            runCatching { Files.deleteIfExists(tmp) }
        }
    }
}
