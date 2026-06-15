package hivens.widget.api

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import hivens.widget.model.WidgetInstance
import kotlinx.coroutines.flow.drop
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.serializer

/**
 * Per-instance runtime state, the writable counterpart of props: props are the
 * editor's static config for an instance, this is data the widget itself owns and
 * mutates at runtime (a note's text, a counter, a toggle) and that persists across
 * restarts. Kept OUT of the layout graph -- different lifecycle (a layout undo must
 * not revert a user's notes) and write cadence (per keystroke) -- so the host backs
 * it with its own store, keyed by instanceId.
 *
 * Widget-facing surface only: read [load], write [store]. Store-management
 * (eviction, orphan GC, flush) lives on the concrete store, not here.
 */
interface WidgetStateHost {
    fun load(instanceId: String): JsonObject?
    fun store(instanceId: String, value: JsonObject)
}

// Reuse the prop Json: same round-trip needs (ignoreUnknownKeys for cross-version
// state files, encodeDefaults so an all-default state serializes explicitly).
inline fun <reified T> decodeWidgetState(stored: JsonObject?): T? =
    stored?.let { runCatching { widgetPropsJson.decodeFromJsonElement(serializer<T>(), it) }.getOrNull() }

inline fun <reified T> encodeWidgetState(value: T): JsonObject =
    widgetPropsJson.encodeToJsonElement(serializer<T>(), value).jsonObject

/**
 * Binds a widget to its own persisted per-instance state. Returns a [MutableState];
 * writing to it updates the UI and persists (the host debounces the disk write).
 * Seeded once from the store keyed on [WidgetInstance.instanceId] (stable across
 * restart); a missing or malformed entry falls back to [default], so a corrupt
 * state file degrades one widget to its default rather than crashing the surface --
 * the same boundary as [rememberProps].
 *
 * Single-writer per id (the instance owns its key), so this is a plain seeded
 * [MutableState], not a `collectAsState` over the store. If a future rule-engine
 * gains the ability to write widget state, this becomes a `collectAsState` and the
 * call-site API stays source-compatible.
 */
@Composable
inline fun <reified T> WidgetInstance.rememberWidgetState(crossinline default: () -> T): MutableState<T> {
    val host = LocalWidgetStateHost.current
    val id = instanceId
    val state = remember(id) { mutableStateOf(decodeWidgetState<T>(host.load(id)) ?: default()) }
    // Keyed on the stable instanceId so the collector is registered once and never
    // re-registers on recomposition. drop(1) skips snapshotFlow's initial emission
    // (the just-loaded seed) so a mount does not rewrite unchanged state.
    LaunchedEffect(id) {
        snapshotFlow { state.value }
            .drop(1)
            .collect { host.store(id, encodeWidgetState(it)) }
    }
    return state
}
