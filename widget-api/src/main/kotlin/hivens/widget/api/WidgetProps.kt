package hivens.widget.api

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import hivens.widget.model.WidgetInstance
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer

// Shared Json for prop decode. ignoreUnknownKeys: a layout written by a
// newer build (extra prop keys) still decodes on an older one.
// encodeDefaults: the editor + registry round-trip explicit values.
val widgetPropsJson: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

// Decode this instance's stored props into the typed prop class T. An
// empty props object decodes to all-defaults (every prop field has a
// default), so an un-tuned widget still gets a valid T. A malformed
// props object (corrupt / cross-version layout file) falls back to
// defaults rather than crashing the surface -- the layout file is
// user-editable, so this is a real boundary.
inline fun <reified T> WidgetInstance.decodeProps(): T =
    runCatching {
        widgetPropsJson.decodeFromJsonElement(serializer<T>(), props)
    }.getOrElse {
        widgetPropsJson.decodeFromJsonElement(serializer<T>(), JsonObject(emptyMap()))
    }

// Composable accessor: memoized on props so the decode runs only when
// stored props change. The returned data class is stable (all-stable
// fields), so Compose can skip recomposition when nothing changed --
// the stability win typed props buy over a raw JsonObject.
@Composable
inline fun <reified T> WidgetInstance.rememberProps(): T =
    remember(props) { decodeProps<T>() }
