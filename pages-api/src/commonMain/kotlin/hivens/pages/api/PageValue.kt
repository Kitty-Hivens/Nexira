package hivens.pages.api

import hivens.widget.model.ScopeLevel
import hivens.widget.model.SourceKey
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * A value an opening hands the page it opens, the pack a pack's page is about say, instead of an argument of the
 * screen. Kept in the history as JSON under [id] and read by the widgets of the page as the source [key], which is
 * null on a page opened without it.
 */
class PageValue<T : Any>(val id: String, private val serializer: KSerializer<T>) {

    val key: SourceKey<T?> = SourceKey(id, ScopeLevel.Page)

    /** [value] as it goes into [Show.values]. */
    fun of(value: T): Pair<String, JsonElement> = id to Json.encodeToJsonElement(serializer, value)

    /** What [values] hold for this, or null where they hold nothing or nothing this build can read. */
    fun read(values: Map<String, JsonElement>): T? =
        values[id]?.let { runCatching { Lenient.decodeFromJsonElement(serializer, it) }.getOrNull() }

    override fun toString(): String = "PageValue($id)"

    private companion object {
        val Lenient = Json { ignoreUnknownKeys = true }
    }
}
