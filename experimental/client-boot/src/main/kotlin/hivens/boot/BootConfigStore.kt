package hivens.boot

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads and writes the boot file over the raw document rather than over a
 * generated serializer.
 *
 * The reason is round-tripping: a newer build may have written keys this one has
 * no field for, and rewriting the file must not discard them. A `@Serializable`
 * data class with `ignoreUnknownKeys` reads such a file happily and then drops
 * everything it did not recognise on the next write, which turns a downgrade
 * into data loss. So the document is the source, the typed view is derived from
 * it, and a write merges the known keys back into the document it came from.
 *
 * No read path throws. This file is consulted before a log, a locale or a window
 * exists, so there is nowhere for an exception to go and nothing to render it
 * with. A failure produces a [BootState] the caller acts on instead -- and the
 * two failures are not the same: a missing file is a first run, an unreadable
 * one is damage, and treating them alike either drops a fresh install into an
 * error state or overwrites a user's set with defaults.
 *
 * The write is handed in ([publish]) instead of imported. Durable writing is a
 * tmp-fsync-rename sequence that already exists elsewhere in the tree, and
 * copying it here would put a durability primitive in two places where the two
 * copies drift. The same reason `nx-ui` takes a publish lambda rather than
 * importing the launcher's helper.
 */
class BootConfigStore(
    private val file: Path,
    private val publish: (Path, String) -> Unit,
) {
    private val json = Json { prettyPrint = true }

    /** The document as it is on disk, or an empty one when there is nothing usable. */
    private fun document(): JsonObject =
        runCatching { json.parseToJsonElement(Files.readString(file)).jsonObject }
            .getOrElse { JsonObject(emptyMap()) }

    /**
     * What the file turned out to be. The caller has to tell a first run from a
     * corrupt file: one is seeded from the bundled default, the other must load
     * nothing, and treating them alike either drops a fresh install into an
     * error state or silently overwrites a user's set with defaults.
     */
    fun state(): BootState {
        if (!Files.exists(file)) return BootState.Absent
        val doc = runCatching { json.parseToJsonElement(Files.readString(file)).jsonObject }
            .getOrElse { return BootState.Unreadable(it.message ?: it::class.simpleName.orEmpty()) }
        return BootState.Loaded(fromDocument(doc))
    }

    /**
     * The typed view of a document that parsed. An entry that is not an object,
     * or has no id, is skipped rather than failing the whole read: one malformed
     * entry must not cost the user every other module.
     */
    internal fun fromDocument(doc: JsonObject): BootConfig {
        val bootstrap = doc["bootstrap"]?.asStringList().orEmpty()
        val modules = (doc["modules"] as? JsonArray).orEmpty().mapNotNull { it.asModuleEntry() }
        return BootConfig(bootstrap, modules)
    }

    /**
     * Writes [config] back, preserving every key the document carried that this
     * build does not model.
     */
    fun write(config: BootConfig) {
        val merged = buildJsonObject {
            document().forEach { (key, value) -> if (key != "bootstrap" && key != "modules") put(key, value) }
            put("bootstrap", buildJsonArray { config.bootstrap.forEach { add(JsonPrimitive(it)) } })
            put("modules", buildJsonArray {
                config.modules.forEach { entry ->
                    add(buildJsonObject {
                        put("id", JsonPrimitive(entry.id))
                        put("enabled", JsonPrimitive(entry.enabled))
                    })
                }
            })
        }
        publish(file, json.encodeToString(JsonObject.serializer(), merged))
    }
}

private fun JsonElement.asStringList(): List<String>? =
    (this as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

private fun JsonElement.asModuleEntry(): ModuleEntry? {
    val obj = this as? JsonObject ?: return null
    val id = (obj["id"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
    val enabled = runCatching { obj["enabled"]?.jsonPrimitive?.boolean }.getOrNull() ?: true
    return ModuleEntry(id, enabled)
}

private fun JsonArray?.orEmpty(): List<JsonElement> = this ?: emptyList()
