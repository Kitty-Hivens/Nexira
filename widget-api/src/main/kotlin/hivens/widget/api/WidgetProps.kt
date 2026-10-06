package hivens.widget.api

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import hivens.widget.model.WidgetInstance
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer

// Shared Json for prop decode. ignoreUnknownKeys: a layout written by a
// newer build (extra prop keys) still decodes on an older one.
// encodeDefaults: the editor + registry round-trip explicit values.
val widgetPropsJson: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

// Decode this instance's stored props into the typed prop class T. An
// empty props object decodes to all-defaults (every prop field has a
// default), so an un-tuned widget still gets a valid T. A field that
// cannot be read (corrupt / cross-version layout file) falls back to its
// default rather than crashing the surface -- the layout file is
// user-editable, so this is a real boundary. Only that field: see
// [readableProps].
inline fun <reified T> WidgetInstance.decodeProps(): T {
    val serializer = serializer<T>()
    return runCatching { widgetPropsJson.decodeFromJsonElement(serializer, props) }
        .recoverCatching { widgetPropsJson.decodeFromJsonElement(serializer, readableProps(serializer, props)) }
        .getOrElse { widgetPropsJson.decodeFromJsonElement(serializer, JsonObject(emptyMap())) }
}

/**
 * [props] without the fields [serializer] cannot read.
 *
 * A record that fails to decode used to fall back whole, so one unreadable
 * field reset every prop on the widget. Each field is tried on its own instead
 * and only the ones that fail are left out, to take their defaults. The editor's
 * panel reads through this too, so it shows the values the widget draws with
 * rather than the stored ones it could not use.
 */
fun readableProps(serializer: KSerializer<*>, props: JsonObject): JsonObject {
    if (decodes(serializer, props)) return props
    return JsonObject(props.filter { (key, value) -> decodes(serializer, JsonObject(mapOf(key to value))) })
}

private fun decodes(serializer: KSerializer<*>, props: JsonObject): Boolean =
    runCatching { widgetPropsJson.decodeFromJsonElement(serializer, props) }.isSuccess

// Composable accessor: memoized on props so the decode runs only when
// stored props change. The returned data class is stable (all-stable
// fields), so Compose can skip recomposition when nothing changed --
// the stability win typed props buy over a raw JsonObject.
@Composable
inline fun <reified T> WidgetInstance.rememberProps(): T =
    remember(props) { decodeProps<T>() }
