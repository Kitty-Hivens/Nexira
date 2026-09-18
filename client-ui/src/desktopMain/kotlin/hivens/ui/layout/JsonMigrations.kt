package hivens.ui.layout

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import hivens.widget.model.GRID_MAX
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * The half of the ladder that runs BEFORE the graph is decoded.
 *
 * [Migrations] takes a `LayoutGraph` and can therefore only change what is
 * inside one: a widget's kind, its props, where it sits. It cannot change the
 * SHAPE of the file, because by the time it runs the decoder has already read
 * it, and the shared Json ignores unknown keys. A field removed from the model
 * is gone before the ladder sees it, so a structural change made there would
 * silently drop whatever it was supposed to carry across.
 *
 * So structure migrates here, on the raw object, and meaning migrates there, on
 * the decoded one. Both are keyed off the same `schema_version`, and both run on
 * every path that reads a graph from outside this build: the layout file and an
 * imported preset.
 */
internal object JsonMigrations {

    /**
     * Brings the raw `graph` object from [fromVersion] up to
     * [LayoutReconcile.CURRENT_SCHEMA]. A version at or above the current one
     * is returned untouched, which is also what a file this build just wrote
     * hits.
     */
    fun apply(fromVersion: Int, graph: JsonObject): JsonObject {
        require(fromVersion >= 1) { "schema_version must be >= 1, got $fromVersion" }
        var current = graph
        for (step in fromVersion until LayoutReconcile.CURRENT_SCHEMA) {
            current = step(step + 1)(current)
        }
        return current
    }

    private fun step(toVersion: Int): (JsonObject) -> JsonObject = when (toVersion) {
        10 -> ::collapseOrientationsIntoPlacement
        11 -> ::wrapSlotsInGeneralFamily
        else -> { it -> it }
    }

    /**
     * A surface's slots become one family's slots, named `general`.
     *
     * Everything a file written before families describes is what the surface
     * shows when nothing has asked for anything else, which is exactly what the
     * general family is. So the step is a wrap and not a translation: the slot map
     * moves down one level under a name it already had implicitly, and no widget,
     * position or arrangement is touched.
     *
     * Only the top level moves. A container widget's `children` still keys slots
     * directly, because a family is a property of a SURFACE -- the thing code
     * navigates and swaps -- and a widget nested inside one is already inside
     * whichever family is showing it.
     */
    private fun wrapSlotsInGeneralFamily(graph: JsonObject): JsonObject {
        val surfaces = graph["surfaces"]?.asObjectOrNull() ?: return graph
        return buildJsonObject {
            graph.forEach { (key, value) -> if (key != "surfaces") put(key, value) }
            put("surfaces", JsonObject(surfaces.mapValues { (_, layout) -> wrapSurface(layout) }))
        }
    }

    private fun wrapSurface(layout: JsonElement): JsonElement {
        val obj = layout.asObjectOrNull() ?: return layout
        // Already wrapped: a hand-edited file, or one this build wrote and then
        // re-read through a lower stamp. Wrapping twice would bury the reader's
        // arrangement under a family nothing renders.
        if (obj["families"] != null) return layout
        val slots = obj["slots"] ?: JsonObject(emptyMap())
        return buildJsonObject {
            obj.forEach { (key, value) -> if (key != "slots") put(key, value) }
            put(
                "families",
                buildJsonObject {
                    put("general", buildJsonObject { put("slots", slots) })
                },
            )
        }
    }

    /**
     * Five slot orientations become one flow with a direction and a line length,
     * plus one placement mode with a unit, and three per-widget position fields
     * become one record.
     *
     * The mapping is one to one in both directions, so nothing is lost and no
     * arrangement moves. A stack is a vertical flow, a row a horizontal one, a
     * grid a horizontal flow that wraps into equal cells, a canvas a placement
     * slot measuring in dp, and a cube grid a placement slot measuring in cells
     * of the lattice its column count already described.
     *
     * The widget's own record is read against its slot's OLD orientation,
     * because that is what decided which of `weight`, `canvas` and `cell` meant
     * anything. A widget carrying all three (which the old model allowed, and
     * which flipping a slot back and forth produced) keeps the one its slot was
     * actually reading and drops the rest, which is the same answer the renderer
     * was giving on screen.
     */
    private fun collapseOrientationsIntoPlacement(graph: JsonObject): JsonObject {
        val surfaces = graph["surfaces"]?.asObjectOrNull() ?: return graph
        return buildJsonObject {
            graph.forEach { (key, value) -> if (key != "surfaces") put(key, value) }
            put("surfaces", JsonObject(surfaces.mapValues { (_, layout) -> migrateSurface(layout) }))
        }
    }

    private fun migrateSurface(layout: JsonElement): JsonElement {
        val obj = layout.asObjectOrNull() ?: return layout
        val slots = obj["slots"]?.asObjectOrNull() ?: return layout
        return buildJsonObject {
            obj.forEach { (key, value) -> if (key != "slots") put(key, value) }
            put("slots", JsonObject(slots.mapValues { (_, slot) -> migrateSlot(slot) }))
        }
    }

    private fun migrateSlot(slot: JsonElement): JsonElement {
        val obj = slot.asObjectOrNull() ?: return slot
        val orientation = obj["orientation"]?.asStringOrNull()?.trim().orEmpty()
        // Clamped exactly where the old renderer clamped it. It read the column
        // count as coerceAtLeast(1), so a zero or a negative in a hand-edited file
        // drew a one-column grid; carrying the raw number across would turn that
        // into a free slot measuring in dp, and a cell address of (3, 2) would be
        // read as three dp by two.
        val columns = obj.int("gridColumns", LEGACY_DEFAULT_COLUMNS).coerceIn(1, GRID_MAX)

        // A slot whose widgets are not a list is not a slot. Emptying it here would
        // hand the user a blank pane with no word said, where before the decoder
        // threw and the repository fell back to the bundled default with a line in
        // the log. The throw is the honest answer and the caller already catches it.
        val widgets = obj["widgets"]?.let {
            it.asArrayOrNull() ?: throw IllegalArgumentException("a slot's widgets must be a list, got ${it::class.simpleName}")
        } ?: JsonArray(emptyList())
        val migrated = widgets.map { migrateWidget(it, orientation) }

        return buildJsonObject {
            // Anything this step does not own is carried across at every level, not
            // only on the widget: the step rewrites a shape and does not get to
            // decide what else a newer build put beside it.
            obj.forEach { (key, value) -> if (key !in RETIRED_SLOT_KEYS) put(key, value) }
            put("widgets", JsonArray(migrated))
            when (orientation) {
                "Canvas" -> {
                    put("flow", JsonNull)
                    put("grid", 0)
                }
                "CubeGrid" -> {
                    put("flow", JsonNull)
                    put("grid", columns)
                }
                "Grid" -> {
                    put("flow", flowOf(horizontal = true, wrap = columns, uniform = true))
                    put("grid", 0)
                }
                "Row" -> {
                    put("flow", flowOf(horizontal = true, wrap = 0, uniform = false))
                    put("grid", 0)
                }
                // Column, the sentinel a newer build's value folded to, an absent
                // key, and anything unrecognised. All of them rendered as a plain
                // column before this step, so all of them become one.
                else -> {
                    put("flow", flowOf(horizontal = false, wrap = 0, uniform = false))
                    put("grid", 0)
                }
            }
        }
    }

    private fun migrateWidget(widget: JsonElement, slotOrientation: String): JsonElement {
        val obj = widget.asObjectOrNull() ?: return widget
        val placement = placementFor(obj, slotOrientation)

        return buildJsonObject {
            // Everything the widget carried that this step does not own is copied
            // across untouched, including keys a newer build may have added: the
            // step rewrites a shape, it does not get to decide what else belongs.
            obj.forEach { (key, value) ->
                if (key !in RETIRED_WIDGET_KEYS) put(key, value)
            }
            obj["children"]?.asObjectOrNull()?.let { children ->
                put("children", JsonObject(children.mapValues { (_, c) -> migrateSlot(c) }))
            }
            if (placement != null) put("placement", placement)
        }
    }

    private fun placementFor(widget: JsonObject, slotOrientation: String): JsonObject? {
        val weight = widget.float("weight")
        val canvas = widget["canvas"]?.asObjectOrNull()
        val cell = widget["cell"]?.asObjectOrNull()

        return when (slotOrientation) {
            "CubeGrid" -> cell?.let {
                placement(
                    x = it.float("col"), y = it.float("row"),
                    width = it.float("colSpan", 1f), height = it.float("rowSpan", 1f),
                    z = it.int("z"),
                )
            }
            "Canvas" -> canvas?.let {
                placement(
                    x = it.float("x"), y = it.float("y"),
                    width = it.float("width"), height = it.float("height"),
                    z = it.int("z"),
                )
            }
            // A flow read the weight first and the canvas size as an upper bound.
            // The offset and the layer meant nothing there, so they are not carried:
            // writing them would make a widget that had merely visited a canvas look
            // deliberately placed to every later reader.
            else -> {
                val w = canvas?.float("width") ?: 0f
                val h = canvas?.float("height") ?: 0f
                if (weight <= 0f && w <= 0f && h <= 0f) null
                else placement(width = w, height = h, weight = weight)
            }
        }
    }

    private fun placement(
        x: Float = 0f,
        y: Float = 0f,
        width: Float = 0f,
        height: Float = 0f,
        z: Int = 0,
        weight: Float = 0f,
    ): JsonObject = buildJsonObject {
        put("anchor", "topStart")
        put("x", x)
        put("y", y)
        put("width", width)
        put("height", height)
        put("z", z)
        put("weight", weight)
    }

    private fun flowOf(horizontal: Boolean, wrap: Int, uniform: Boolean): JsonObject = buildJsonObject {
        put("direction", if (horizontal) "horizontal" else "vertical")
        put("wrap", wrap)
        put("uniform", uniform)
    }

    /** What the old shape kept per widget and the new one folds into `placement`. */
    private val RETIRED_WIDGET_KEYS = setOf("weight", "canvas", "cell", "children")

    /** What the old shape kept per slot and the new one says as a flow and a unit. */
    private val RETIRED_SLOT_KEYS = setOf("orientation", "gridColumns", "widgets")

    /** The old default column count, which a file written before the key existed implies. */
    private const val LEGACY_DEFAULT_COLUMNS = 2

    // ── Reading a file somebody may have edited by hand ──────────────────

    private fun JsonElement.asObjectOrNull(): JsonObject? = this as? JsonObject

    private fun JsonElement.asArrayOrNull(): JsonArray? = this as? JsonArray

    private fun JsonElement.asStringOrNull(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    // A number somewhere a number was expected, or the fallback. Guarded against a
    // nested object rather than left to throw: the accessor beside these already
    // forgives a wrong shape, and one field of one widget must not take a file down.
    private fun JsonObject.float(key: String, fallback: Float = 0f): Float =
        (this[key] as? JsonPrimitive)?.floatOrNull ?: fallback

    private fun JsonObject.int(key: String, fallback: Int = 0): Int =
        (this[key] as? JsonPrimitive)?.intOrNull ?: fallback
}
