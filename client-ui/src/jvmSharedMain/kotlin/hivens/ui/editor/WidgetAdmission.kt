package hivens.ui.editor

import hivens.widget.api.KeyDeclaration
import hivens.widget.api.KeyOrigin
import hivens.widget.model.LayoutGraph
import hivens.widget.model.ScopeLevel
import hivens.widget.model.SurfaceId
import hivens.widget.model.WidgetInstance
import hivens.widget.model.WidgetKind

/**
 * Whether a widget can stand on a surface: every source and command it declares in
 * its needs has an answer there. The editor asks before it places one, from the
 * palette or by a drag, and refuses rather than put a widget where it would draw
 * the placeholder for one that is missing what it reads.
 *
 * The answer comes from where each key lives, which its declaration says, and from
 * where the surface is drawn, which the editor's own registry of surfaces says:
 *
 * - A key of the app or of a window answers on every surface, because every surface
 *   is drawn in a window.
 * - A key of the composition answers on every surface too. The editor arranges the
 *   surfaces of the one composition it is part of, and every composition key is
 *   there around the editor as well, made by the composition's level or provided
 *   by its owner.
 * - A key of a page answers only on a surface drawn in a page, which a screen's own
 *   surface is and a shell surface is not. There a key a factory makes is on every
 *   page, and one a page's owner hands it is only on the surfaces whose screen does
 *   ([EditorSurfaceSpec.provides]).
 *
 * An id nothing declares answers nowhere: it names a key that does not exist in this
 * build, a module that is not loaded say, and a widget reading it could only draw
 * the placeholder. [declarationOf] is the registries' answer, as the scope tree has
 * them.
 */
class WidgetAdmission(
    private val declarationOf: (String) -> KeyDeclaration?,
    private val needsOf: (WidgetKind) -> List<String>,
) {
    /** The needs of [kind] that nothing answers on [surface], in the order the widget declared them. */
    fun missing(kind: WidgetKind, surface: SurfaceId, graph: LayoutGraph): List<String> {
        val needs = needsOf(kind)
        if (needs.isEmpty()) return emptyList()
        val page = EditorSurfaces.pageProvides(surface, graph)
        return needs.filterNot { id -> answers(declarationOf(id), page) }
    }

    /**
     * The needs that nothing answers on [surface] for [widget] and for every widget
     * nested in it, each named once. A container goes where its children go, so a
     * container carrying a page's header cannot be moved off that page either.
     */
    fun missing(widget: WidgetInstance, surface: SurfaceId, graph: LayoutGraph): List<String> =
        widget.subtree().flatMap { missing(it.kind, surface, graph) }.distinct().toList()

    /** Whether [kind] may be placed on [surface]. */
    fun admits(kind: WidgetKind, surface: SurfaceId, graph: LayoutGraph): Boolean = missing(kind, surface, graph).isEmpty()

    private fun WidgetInstance.subtree(): Sequence<WidgetInstance> = sequence {
        yield(this@subtree)
        children.values.forEach { slot -> slot.widgets.forEach { yieldAll(it.subtree()) } }
    }

    private fun answers(declaration: KeyDeclaration?, page: Set<String>?): Boolean {
        if (declaration == null) return false
        return when (declaration.level) {
            ScopeLevel.App, ScopeLevel.Window, ScopeLevel.Composition -> true
            ScopeLevel.Page -> page != null && (declaration.origin != KeyOrigin.Owner || declaration.id in page)
        }
    }

    companion object {
        /** Admits everything, for an editor wired without registries to ask. */
        val Open: WidgetAdmission = WidgetAdmission(declarationOf = { null }, needsOf = { emptyList() })
    }
}
