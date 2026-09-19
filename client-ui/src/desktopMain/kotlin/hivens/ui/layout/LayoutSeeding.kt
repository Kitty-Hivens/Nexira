package hivens.ui.layout

import hivens.widget.model.FamilyId
import hivens.widget.model.LayoutGraph
import hivens.widget.model.SlotId
import hivens.widget.model.SurfaceId
import hivens.widget.model.WidgetInstance
import hivens.widget.model.walkInstances

/**
 * A bundled widget added after somebody's first launch, reaching their graph.
 *
 * [LayoutReconcile] seeds surfaces, families and slots the bundled default has
 * gained, and deliberately stops there: a slot present in the default and absent
 * from a user file is always an upstream addition, because the editor cannot
 * create or delete one. A widget is not like that. A widget absent from a slot may
 * be one the user removed, and putting it back on every launch would be the editor
 * undoing their work once a day.
 *
 * So the graph carries no answer, and the answer is kept beside it: the set of
 * bundled ids this graph has ever been OFFERED. A bundled widget that is neither in
 * the graph nor in that set has never been offered, which can only mean it did not
 * exist when the file was written. It is seeded once and joins the set; remove it
 * and it stays removed, because the set remembers the offer rather than the widget.
 *
 * Without this, every widget shipped after a person's first launch is invisible to
 * them forever, and the only way to see one is a surface reset that takes their
 * arrangement with it. Measured on one real graph the day this was written: three
 * bundled widgets missing, from two unrelated releases, neither of them noticed.
 *
 * Pure, so the rule is testable without a disk. The store lives in
 * [SeededWidgets] and the wiring in [LayoutGraphRepository].
 */
object LayoutSeeding {

    data class Result(
        val graph: LayoutGraph,
        /** Every bundled id the graph has now been offered, for the caller to persist. */
        val offered: Set<String>,
        /** What this pass placed, for the log. Empty on the ordinary launch. */
        val added: List<String>,
    )

    /** One bundled widget, and where in the bundle it sits. */
    private data class Place(
        val surface: SurfaceId,
        val family: FamilyId,
        val slot: SlotId,
        val index: Int,
        val widget: WidgetInstance,
    )

    /**
     * Places bundled widgets [user] has never been offered.
     *
     * [offered] null is a graph from before this record existed. Its set is derived
     * rather than guessed at: everything the graph already holds counts as offered,
     * and so does a bundled widget whose KIND already sits in the slot it belongs
     * to, because that is somebody who wanted the control and added it by hand
     * while it could not arrive on its own. Seeding beside it would hand them a
     * second copy of the thing they had just gone and fetched.
     *
     * Run after [LayoutReconcile.reconcile]: a slot the graph is missing entirely
     * arrives from the merge with the bundle's widgets already in it, so by here
     * every slot named below exists.
     */
    fun seed(user: LayoutGraph, default: LayoutGraph, offered: Set<String>?): Result {
        val places = bundledPlaces(default)
        if (places.isEmpty()) return Result(user, offered.orEmpty(), emptyList())

        val userIds = user.walkInstances().map { it.instanceId }.toSet()
        val known = offered ?: initialOffer(places, user, userIds)

        var graph = user
        val added = mutableListOf<String>()
        for (place in places) {
            val id = place.widget.instanceId
            if (id in userIds || id in known) continue
            graph = insert(graph, place) ?: continue
            added += id
        }
        return Result(graph, known + places.map { it.widget.instanceId }, added)
    }

    /**
     * What a graph written before this record is treated as having been offered.
     *
     * Anything it holds, plus anything whose kind already occupies the slot the
     * bundle would put it in. The second half is what stops a hand-added control
     * from being duplicated by the first run that could have delivered it.
     */
    private fun initialOffer(
        places: List<Place>,
        user: LayoutGraph,
        userIds: Set<String>,
    ): Set<String> = buildSet {
        addAll(userIds)
        for (place in places) {
            val kinds = user.surfaces[place.surface]
                ?.family(place.family)
                ?.slots?.get(place.slot)
                ?.widgets.orEmpty()
                .map { it.kind }
            if (place.widget.kind in kinds) add(place.widget.instanceId)
        }
    }

    /** Every bundled widget with the slot and position it holds in the bundle. */
    private fun bundledPlaces(default: LayoutGraph): List<Place> = buildList {
        for ((surfaceId, layout) in default.surfaces) {
            for ((familyId, family) in layout.families) {
                for ((slotId, content) in family.slots) {
                    content.widgets.forEachIndexed { index, widget ->
                        add(Place(surfaceId, familyId, slotId, index, widget))
                    }
                }
            }
        }
    }

    /**
     * Puts one bundled widget into the slot it belongs to, at the position it holds
     * in the bundle, or as near to it as a rearranged slot allows.
     *
     * Null when the slot is not there, which after the merge means the graph is from
     * a newer build that has retired it. Skipping is the same answer the transforms
     * give for a vanished slot, and for the same reason.
     */
    private fun insert(graph: LayoutGraph, place: Place): LayoutGraph? {
        val layout = graph.surfaces[place.surface] ?: return null
        val family = layout.family(place.family) ?: return null
        val content = family.slots[place.slot] ?: return null

        val at = place.index.coerceIn(0, content.widgets.size)
        val grown = content.copy(
            widgets = content.widgets.toMutableList().apply { add(at, place.widget) },
        )
        return graph.copy(
            surfaces = graph.surfaces + (
                place.surface to layout.copy(
                    families = layout.families + (
                        place.family to family.copy(slots = family.slots + (place.slot to grown))
                        ),
                )
                ),
        )
    }
}
