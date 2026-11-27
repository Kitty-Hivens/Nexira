package hivens.pages.api

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/** A screen, by the id it is shown and named by. */
@JvmInline
@Serializable
value class ScreenId(val value: String)

/**
 * What is shown: the screen and the values its page reads, by the ids of the [PageValue]s that read them.
 *
 * [opened] are the values it was opened with. They and the screen are what makes two entries the same page ([page]),
 * so a page that rewrites its values is still the page it was.
 */
@Serializable
data class HistoryEntry(
    val screen: ScreenId,
    val values: Map<String, JsonElement> = emptyMap(),
    val opened: Map<String, JsonElement> = values,
) {
    /**
     * The page this entry shows. Entries that agree on it share what the page keeps: its level, its scroll. The
     * order the values were given in does not matter, at any depth.
     */
    val page: String get() = if (opened.isEmpty()) screen.value else "${screen.value}${ordered(opened)}"
}

/** [values] with every object's keys sorted, at any depth, so equal values read the same. */
private fun ordered(values: Map<String, JsonElement>): JsonObject =
    JsonObject(values.entries.sortedBy { it.key }.associate { (id, value) -> id to ordered(value) })

private fun ordered(value: JsonElement): JsonElement = when (value) {
    is JsonObject -> ordered(value as Map<String, JsonElement>)
    is JsonArray -> JsonArray(value.map(::ordered))
    else -> value
}

/**
 * What is asked to be shown: the screen, the values to open its page with, and for whatever keeps a history of what
 * was shown, where in it this goes.
 *
 * With [over] null it is put on top, and back returns to what was shown. Otherwise the history is cut back to the
 * screen [over] names, opened with no values, and it goes on top of that: a link of a rail shows its section over
 * home, so back from any section comes home and sections do not pile up. Where the history has no [over], it becomes
 * [over] and what is asked for.
 */
@Serializable
data class Show(
    val screen: ScreenId,
    val values: Map<String, JsonElement> = emptyMap(),
    val over: ScreenId? = null,
)
