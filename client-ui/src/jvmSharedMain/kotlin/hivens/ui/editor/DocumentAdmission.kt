package hivens.ui.editor

import hivens.widget.api.KeyDeclaration
import hivens.widget.api.KeyOrigin
import hivens.widget.api.WidgetDescriptor
import hivens.widget.model.Document
import hivens.widget.model.NodePath
import hivens.widget.model.ScopeLevel
import hivens.widget.model.WidgetInstance
import hivens.widget.model.WidgetKind
import hivens.widget.model.contentAt

/**
 * Whether a widget can stand in a slot of the document: every source and command it declares in its needs has an answer
 * there. The editor asks before it places one, from the palette or by a drag, and refuses rather than put a widget where
 * it would draw the placeholder for one missing what it reads.
 *
 * Where a key answers is what its declaration says, and where a slot is drawn is what the path to it says:
 *
 * - A key of the app, a window or the composition answers in every slot the editor arranges.
 * - A key of a page answers only in a slot on a page, which is one inside a slot its container declares as a page
 *   ([WidgetDescriptor.pages]). There a key a factory makes answers on every page, the history's own keys
 *   ([everyPage]) too, and a key a page's owner hands answers only under a container that hands it
 *   ([WidgetDescriptor.hands]).
 * - An id nothing declares answers nowhere: it names a key this build does not have, a module not loaded say.
 */
class DocumentAdmission(
    private val declarationOf: (String) -> KeyDeclaration?,
    private val descriptorOf: (WidgetKind) -> WidgetDescriptor?,
    /** What the owner of a history hands every page it draws. */
    private val everyPage: Set<String> = emptySet(),
) {
    /** The needs of [kind] that nothing answers at [path], in the order the widget declared them. */
    fun missing(kind: WidgetKind, path: NodePath, document: Document): List<String> {
        val needs = descriptorOf(kind)?.needs.orEmpty()
        if (needs.isEmpty()) return emptyList()
        val page = pageAt(path, document)
        return needs.filterNot { answers(declarationOf(it), page) }
    }

    /** The needs nothing answers at [path] for [widget] and every widget inside it, each named once. */
    fun missing(widget: WidgetInstance, path: NodePath, document: Document): List<String> =
        widget.subtree().flatMap { missing(it.kind, path, document) }.distinct().toList()

    fun admits(kind: WidgetKind, path: NodePath, document: Document): Boolean = missing(kind, path, document).isEmpty()

    /**
     * The owner keys a widget at [path] is handed, or null where the slot is not on a page. Walked from the root: each
     * step names the container the next slot belongs to, which says whether that slot is a page and what it hands.
     */
    internal fun pageAt(path: NodePath, document: Document): Set<String>? {
        var onPage = false
        val handed = HashSet(everyPage)
        path.nested.forEachIndexed { depth, step ->
            val above = path.copy(nested = path.nested.take(depth))
            val container = document.contentAt(above)?.widgets?.firstOrNull { it.instanceId == step.parentInstanceId } ?: return null
            val descriptor = descriptorOf(container.kind) ?: return null
            if (step.slot in descriptor.pages) onPage = true
            handed += descriptor.hands
        }
        return if (onPage) handed else null
    }

    private fun answers(declaration: KeyDeclaration?, page: Set<String>?): Boolean = when (declaration?.level) {
        null -> false
        ScopeLevel.App, ScopeLevel.Window, ScopeLevel.Composition -> true
        ScopeLevel.Page -> page != null && (declaration.origin != KeyOrigin.Owner || declaration.id in page)
    }

    private fun WidgetInstance.subtree(): Sequence<WidgetInstance> = sequence {
        yield(this@subtree)
        children.values.forEach { slot -> slot.widgets.forEach { yieldAll(it.subtree()) } }
    }

    companion object {
        /** Admits everything, for an editor wired without registries to ask. */
        val Open: DocumentAdmission = DocumentAdmission(declarationOf = { null }, descriptorOf = { null })
    }
}
