package hivens.ui.screens.custom

import hivens.widget.model.LayoutGraph
import hivens.widget.model.ScreenSpec
import hivens.widget.model.SlotId
import hivens.widget.model.SlotPath
import hivens.widget.model.SurfaceId
import hivens.widget.model.WidgetInstance
import hivens.widget.model.WidgetKind
import hivens.widget.model.flatMapInstances
import hivens.widget.model.insertWidget
import hivens.widget.model.traverse
import hivens.widget.model.walkInstances
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull

/**
 * The links that open a made screen: one widget kind, found by the id in its props.
 *
 * The model knows screens and knows nothing of which widgets point at them, so
 * putting a link on the rail when a screen is made and clearing every link when
 * it is deleted are done here, where the kind is known.
 */
internal object ScreenLinks {
    val KIND = WidgetKind("nav.screen")

    /** Where a new screen's link goes: the bottom of the rail's main list. */
    private val RAIL = SlotPath(SurfaceId("appshell.leftrail"), SlotId("top"))

    /**
     * The id a link widget points at, or null when [widget] is not a link. A prop of
     * the wrong shape is no link either, rather than a throw inside a reset or a
     * screen delete: the layout file is somebody's to edit.
     */
    fun target(widget: WidgetInstance): String? =
        if (widget.kind != KIND) null else (widget.props["screen"] as? JsonPrimitive)?.contentOrNull

    /**
     * Puts a link to [spec] at the end of the rail, unless one already exists
     * anywhere. Identity when the rail is not in the graph or already links it.
     */
    fun ensureLink(graph: LayoutGraph, spec: ScreenSpec): LayoutGraph {
        if (graph.walkInstances().any { target(it) == spec.id }) return graph
        val rail = graph.traverse(RAIL) ?: return graph
        val link = WidgetInstance(
            kind = KIND,
            instanceId = "screen-link-${spec.id}",
            props = buildJsonObject { put("screen", JsonPrimitive(spec.id)) },
        )
        return graph.insertWidget(RAIL, link, rail.widgets.size)
    }

    /** [ensureLink] for every made screen, which is what a full reset leaves needing. */
    fun ensureLinks(graph: LayoutGraph): LayoutGraph = graph.screens.fold(graph) { g, spec -> ensureLink(g, spec) }

    /** Every link to [id], wherever it was put, gone. */
    fun removeLinks(graph: LayoutGraph, id: String): LayoutGraph =
        if (graph.walkInstances().none { target(it) == id }) graph
        else graph.flatMapInstances { w -> if (target(w) == id) emptyList() else listOf(w) }
}
