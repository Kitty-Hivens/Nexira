package hivens.ui.settings

import hivens.core.api.interfaces.ISettingsService
import hivens.core.data.SettingsData
import hivens.core.io.AtomicFiles
import hivens.core.io.readUtf8
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import java.nio.file.Path

/**
 * The launcher's former single settings file, `settings.json`, as the settings domains see it: read
 * for what it held of a domain before the domain had a file, and written with the few fields a domain
 * mirrors there for a build from before the move (see [hivens.widget.api.SettingsDomain.mirrored]).
 */
interface LegacySettings {
    /** The file as it is now, or null where there is none or it cannot be read. */
    fun read(): JsonObject?

    /** Puts [fields] into the file, every other field left as it is. */
    fun write(fields: Map<String, JsonElement>)
}

/**
 * [file] read and written directly, for a reader that runs while nothing else writes it: before the
 * host is up, and in tests. Every write is the whole file, replaced atomically.
 */
class LegacySettingsFile(private val file: Path) : LegacySettings {
    override fun read(): JsonObject? = runCatching {
        if (Files.isRegularFile(file)) json.parseToJsonElement(readUtf8(file)).jsonObject else null
    }.getOrNull()

    override fun write(fields: Map<String, JsonElement>) {
        val merged = JsonObject(read().orEmpty() + fields)
        AtomicFiles.writeString(file, json.encodeToString(JsonObject.serializer(), merged))
    }

    private companion object {
        val json = Json { prettyPrint = true }
    }
}

/**
 * [file] as the settings service keeps it: read directly, and written through [service], so the copy
 * it holds in memory has the fields too and its next save does not put back what they were.
 */
class LegacySettingsThroughService(
    private val file: Path,
    private val service: ISettingsService,
    private val json: Json,
) : LegacySettings {
    override fun read(): JsonObject? = LegacySettingsFile(file).read()

    override fun write(fields: Map<String, JsonElement>) {
        service.updateSettings { current ->
            val now = json.encodeToJsonElement(SettingsData.serializer(), current).jsonObject
            json.decodeFromJsonElement(SettingsData.serializer(), JsonObject(now + fields))
        }
    }
}
