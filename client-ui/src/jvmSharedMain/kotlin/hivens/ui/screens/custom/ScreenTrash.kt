package hivens.ui.screens.custom

import hivens.widget.model.LayoutGraph
import hivens.widget.model.ScreenSpec
import hivens.widget.model.SlotPath
import hivens.widget.model.SurfaceLayout
import hivens.widget.model.WidgetInstance
import hivens.widget.model.blankScreenSurface
import hivens.widget.model.insertWidget
import hivens.widget.model.screen
import hivens.widget.model.traverse
import hivens.widget.model.walkInstances

/**
 * A made screen as it was the moment it was deleted: its record, its page, and
 * each link to it with the slot it stood in and its place there.
 *
 * What lets "restore" bring back exactly that screen. The editor's undo would
 * take back whatever was done last, which after a few more edits is no longer the
 * deletion.
 */
internal data class DeletedScreen(
    val spec: ScreenSpec,
    val surface: SurfaceLayout,
    val links: List<StoodAt>,
) {
    data class StoodAt(val path: SlotPath, val index: Int, val widget: WidgetInstance)
}

internal object ScreenTrash {

    /** The screen [id] as it stands in [graph], or null when there is none. */
    fun capture(graph: LayoutGraph, id: String): DeletedScreen? {
        val spec = graph.screen(id) ?: return null
        val links = buildList {
            graph.surfaces.forEach { (surface, layout) ->
                layout.families.forEach { (family, slots) ->
                    slots.slots.forEach { (slot, content) ->
                        content.widgets.forEachIndexed { index, widget ->
                            if (ScreenLinks.target(widget) == id) {
                                add(DeletedScreen.StoodAt(SlotPath(surface, slot, family = family), index, widget))
                            }
                        }
                    }
                }
            }
        }
        return DeletedScreen(spec, graph.surfaces[spec.surface] ?: blankScreenSurface(), links)
    }

    /**
     * Puts [deleted] back: the record, the page, and each link where it stood, so
     * far as that slot is still there. A link that had nowhere to go back to is
     * put at the bottom of the rail, so the screen is never back with no way to it.
     * Identity when the id or the surface has been taken since.
     */
    fun restore(graph: LayoutGraph, deleted: DeletedScreen): LayoutGraph {
        if (graph.screen(deleted.spec.id) != null || deleted.spec.surface in graph.surfaces) return graph
        var out = graph.copy(
            screens = graph.screens + deleted.spec,
            surfaces = graph.surfaces + (deleted.spec.surface to deleted.surface),
        )
        val taken = out.walkInstances().mapTo(HashSet()) { it.instanceId }
        deleted.links.forEach { (path, index, widget) ->
            if (widget.instanceId !in taken && out.traverse(path) != null) out = out.insertWidget(path, widget, index)
        }
        return ScreenLinks.ensureLink(out, deleted.spec)
    }
}
