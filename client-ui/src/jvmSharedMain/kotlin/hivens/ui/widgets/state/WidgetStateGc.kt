package hivens.ui.widgets.state

import hivens.widget.model.Document
import hivens.widget.model.widgets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.time.Duration.Companion.milliseconds

/**
 * Prunes the state of widgets that are gone. Keyed off the document the launcher draws rather than off the editor's
 * remove calls: widgets also go below the editor (a reset, going back to the standard interface, a document renamed on
 * load), and every one of those changes the document, so one collector that keeps the state of the widgets it holds
 * covers them all.
 *
 * Debounced: a drag changes the document on every frame, and what goes only ever shrinks on a removal, none of which
 * is in a hurry. Runs on the app scope, so it sweeps whether or not anything stateful is drawn.
 *
 * What the document holds is not all that is kept. [alsoReferenced] names the widgets something can still bring back:
 * an undo, a deleted screen put back, a saved preset, the build before this one reading the arrangement it saved.
 *
 * Nothing is pruned while the document is one this build does not write ([writable]). The standard document is then
 * drawn in place of a file left as it is, and the state of every widget in that file would be swept as gone: going
 * back to the build that reads it brought the arrangement back with every note and list empty.
 */
@OptIn(FlowPreview::class)
class WidgetStateGc(
    documents: StateFlow<Document>,
    writable: () -> Boolean,
    store: WidgetStateStore,
    scope: CoroutineScope,
    alsoReferenced: suspend () -> Set<String> = { emptySet() },
) {
    init {
        scope.launch {
            documents.debounce(GC_DEBOUNCE_MS.milliseconds).collect { document ->
                if (!writable()) return@collect
                store.retain(document.instanceIds() + alsoReferenced())
            }
        }
    }

    private companion object {
        const val GC_DEBOUNCE_MS = 1_000L
    }
}

/** Every widget's name in every composition of the document. */
fun Document.instanceIds(): Set<String> = compositions.values.flatMapTo(HashSet()) { c -> c.widgets().map { it.instanceId } }

/**
 * Every widget's name anywhere in [element], read as plain JSON, so an arrangement of any age gives its names: a
 * preset's document or the graph an older build saved, which nothing here decodes any more.
 */
fun instanceIdsIn(element: JsonElement): Set<String> = HashSet<String>().also { collectInstanceIds(element, it) }

private fun collectInstanceIds(element: JsonElement, into: MutableSet<String>) {
    when (element) {
        is JsonObject -> element.forEach { (key, value) ->
            if (key == INSTANCE_ID_KEY && value is JsonPrimitive && value.isString) into += value.content
            else collectInstanceIds(value, into)
        }
        is JsonArray -> element.forEach { collectInstanceIds(it, into) }
        else -> Unit
    }
}

/** The wire name of [hivens.widget.model.WidgetInstance.instanceId]. */
private const val INSTANCE_ID_KEY = "instance_id"
