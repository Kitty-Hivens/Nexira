package hivens.widget.model

import kotlinx.serialization.Serializable

/**
 * A screen somebody made: a name, an icon, and the surface it opens.
 *
 * A screen is a point of unfolding and not a widget. It is entered and left, and
 * entering it builds its surface from nothing, which is why it lives beside the
 * surfaces rather than inside one: nothing contains it, things open it. The
 * surface holds the content, the way every built-in screen's surface does, so a
 * custom screen is arranged, reset, scrolled and saved by exactly the machinery
 * the built-in ones use.
 *
 * [id] never changes and is what a link to the screen names. [surface] never
 * changes either, so the content cannot be orphaned by a rename. [icon] is a name
 * from the app's screen icon set, and a name this build does not know draws the
 * default one.
 */
@Serializable
data class ScreenSpec(
    val id: String,
    val title: String = "",
    val icon: String = "",
    val surface: SurfaceId,
)

/** The slot every made screen holds its content in. */
val SCREEN_MAIN_SLOT: SlotId = SlotId("main")

/** The surface a freshly made screen starts with: one empty column, static. */
fun blankScreenSurface(): SurfaceLayout = SurfaceLayout(slots = mapOf(SCREEN_MAIN_SLOT to SlotContent()))

/** The screen named [id], or null when there is none. */
fun LayoutGraph.screen(id: String): ScreenSpec? = screens.firstOrNull { it.id == id }

/** The screen whose content is [surface], or null when that surface is no made screen's. */
fun LayoutGraph.screenOn(surface: SurfaceId): ScreenSpec? = screens.firstOrNull { it.surface == surface }

/**
 * Adds [spec] with a blank surface. Identity when a screen with that id, or a
 * surface with that id, is already there: an id names one thing, and making a
 * second screen over somebody's existing surface would hand it their content.
 */
fun LayoutGraph.addScreen(spec: ScreenSpec): LayoutGraph {
    if (screen(spec.id) != null || spec.surface in surfaces) return this
    return copy(screens = screens + spec, surfaces = surfaces + (spec.surface to blankScreenSurface()))
}

/**
 * Changes what a screen is called or how it looks. The id and the surface are
 * kept whatever [edit] returns, because everything that points at the screen
 * points through them.
 */
fun LayoutGraph.updateScreen(id: String, edit: (ScreenSpec) -> ScreenSpec): LayoutGraph {
    val current = screen(id) ?: return this
    val next = edit(current).copy(id = current.id, surface = current.surface)
    if (next == current) return this
    return copy(screens = screens.map { if (it.id == id) next else it })
}

/**
 * Removes the screen and its surface. What else points at it, a link on the rail
 * or anywhere else, is the app's to clear, because only the app knows which widgets
 * are links.
 */
fun LayoutGraph.removeScreen(id: String): LayoutGraph {
    val spec = screen(id) ?: return this
    return copy(screens = screens - spec, surfaces = surfaces - spec.surface)
}

/**
 * Puts a made screen's surface back to the blank one it started with.
 *
 * The bundled default has nothing to say about a surface somebody made, and the
 * general reset answers that by deleting the surface, which here would leave a
 * screen pointing at nothing. Blank is what this surface's default actually is.
 */
fun LayoutGraph.resetScreenSurface(surface: SurfaceId): LayoutGraph {
    if (screenOn(surface) == null) return this
    return copy(surfaces = surfaces + (surface to blankScreenSurface()))
}

/**
 * Adds the screens of [other] this graph does not have, with their surfaces.
 *
 * For the two moves that replace a graph wholesale, a full reset and a saved
 * arrangement loaded over the current one. Both are about how things are arranged,
 * and neither should take away a screen somebody made and filled.
 */
fun LayoutGraph.withScreensFrom(other: LayoutGraph): LayoutGraph {
    val missing = other.screens.filter { spec -> screen(spec.id) == null && spec.surface !in surfaces }
    if (missing.isEmpty()) return this
    val carried = missing.associate { it.surface to (other.surfaces[it.surface] ?: blankScreenSurface()) }
    return copy(screens = screens + missing, surfaces = surfaces + carried)
}

/**
 * Makes the screen list one a renderer can trust: one record per id and per
 * surface, the first kept, and every screen with a surface to open.
 *
 * A file edited by hand, or two arrangements merged, can say either twice, and a
 * screen whose surface went missing would open onto a pane nothing can be put in.
 * Identity when there is nothing to repair.
 */
fun LayoutGraph.normalizeScreens(): LayoutGraph {
    val ids = HashSet<String>()
    val owned = HashSet<SurfaceId>()
    val kept = screens.filter { it.id.isNotBlank() && ids.add(it.id) && owned.add(it.surface) }
    val missing = kept.filter { it.surface !in surfaces }
    if (kept.size == screens.size && missing.isEmpty()) return this
    return copy(screens = kept, surfaces = surfaces + missing.associate { it.surface to blankScreenSurface() })
}
