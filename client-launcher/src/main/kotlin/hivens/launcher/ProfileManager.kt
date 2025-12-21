package hivens.launcher

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.reflect.TypeToken
import hivens.core.data.InstanceProfile
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.HashMap

class ProfileManager(
    private val workDir: Path,
    private val gson: Gson
) {
    private val log = LoggerFactory.getLogger(ProfileManager::class.java)
    private val fileName = "profiles.json"
    private val profiles: MutableMap<String, InstanceProfile> = HashMap()

    var lastServerId: String? = null
        private set

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

    fun setLastServerId(lastServerId: String) {
        this.lastServerId = lastServerId
        save()
    }

    private fun load() {
        val file = workDir.resolve(fileName)
        if (!Files.exists(file)) return

        try {
            Files.newBufferedReader(file).use { reader ->
                val root = gson.fromJson(reader, JsonElement::class.java)

                if (root.isJsonObject) {
                    val json = root.asJsonObject

                    if (json.has("profiles") || json.has("lastServerId")) {
                        if (json.has("lastServerId")) {
                            this.lastServerId = json.get("lastServerId").asString
                        }
                        if (json.has("profiles")) {
                            val type = object : TypeToken<Map<String, InstanceProfile>>() {}.type
                            val loaded: Map<String, InstanceProfile>? = gson.fromJson(json.get("profiles"), type)
                            if (loaded != null) profiles.putAll(loaded)
                        }
                    } else {
                        val type = object : TypeToken<Map<String, InstanceProfile>>() {}.type
                        val loaded: Map<String, InstanceProfile>? = gson.fromJson(root, type)
                        if (loaded != null) profiles.putAll(loaded)
                    }
                }
            }
            log.info("Loaded {} profiles. Last server: {}", profiles.size, lastServerId)

        } catch (e: IOException) {
            log.error("Failed to load profiles from {}", file, e)
        }
    }

    private fun save() {
        val file = workDir.resolve(fileName)
        try {
            Files.newBufferedWriter(file).use { writer ->
                val data = mapOf(
                    "lastServerId" to lastServerId,
                    "profiles" to profiles
                )
                gson.toJson(data, writer)
            }
        } catch (e: IOException) {
            log.error("Failed to save profiles!", e)
        }
    }
}
