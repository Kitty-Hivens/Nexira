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

    /**
     * The values of this that still exist, for the module that knows which do: a page opened with one outside them is
     * about something gone, and whatever keeps a history of the pages shown leaves it out. A value nothing answers this
     * for is never gone. See [presentKey].
     */
    val present: SourceKey<Set<JsonElement>> = presentKey(id)

    /** [value] as it goes into [Show.values]. */
    fun of(value: T): Pair<String, JsonElement> = id to json(value)

    /** [value] as it is kept, which is what [present] holds. */
    fun json(value: T): JsonElement = Json.encodeToJsonElement(serializer, value)

    /** What [values] hold for this, or null where they hold nothing or nothing this build can read. */
    fun read(values: Map<String, JsonElement>): T? =
        values[id]?.let { runCatching { Lenient.decodeFromJsonElement(serializer, it) }.getOrNull() }

    override fun toString(): String = "PageValue($id)"

    private companion object {
        val Lenient = Json { ignoreUnknownKeys = true }
    }
}

/**
 * The values that still exist of the page value whose id is [valueId], see [PageValue.present]. By the id alone, for
 * whatever keeps entries it only knows the ids of, and has no [PageValue] to ask.
 */
fun presentKey(valueId: String): SourceKey<Set<JsonElement>> = SourceKey("$valueId.present", ScopeLevel.App)
