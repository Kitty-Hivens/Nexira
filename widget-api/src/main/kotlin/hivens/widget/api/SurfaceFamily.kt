package hivens.widget.api

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.staticCompositionLocalOf
import hivens.widget.model.FamilyId
import hivens.widget.model.SurfaceId

/**
 * Which family each surface is currently showing.
 *
 * A surface carries every family it has at once and shows one, and WHICH one is
 * not a property of the layout: it is what the app is doing right now. Opening a
 * project switches the right rail to the family that describes projects, leaving
 * it switches back, and neither writes anything, so the arrangement the reader
 * built for each family is still there when they return to it.
 *
 * Keyed by surface, deliberately. An earlier shape carried the live family down
 * the composition as one value, which meant a slot of a DIFFERENT surface
 * rendered inside this one inherited a family it had never heard of and read an
 * empty slot. A surface's family is that surface's business.
 *
 * Snapshot-backed, so a switch recomposes exactly the slots that read it.
 */
@Stable
class SurfaceFamilies {
    private val active = mutableStateMapOf<SurfaceId, FamilyId>()

    /** What [surface] is showing, [FamilyId.GENERAL] until something says otherwise. */
    fun activeIn(surface: SurfaceId): FamilyId = active[surface] ?: FamilyId.GENERAL

    /**
     * How many screens currently want [surface] off its general family.
     *
     * Not snapshot-backed: nothing renders from it, it only decides when [active]
     * may be cleared. Written from composition effects, which run on one thread.
     */
    private val claims = mutableMapOf<SurfaceId, Int>()

    /**
     * Shows [family] on [surface], until as many [reset] calls come back.
     *
     * Counted, because two screens that want the same family OVERLAP: the shell
     * animates between them, so the arriving one switches before the leaving one
     * is disposed. An unconditional reset then took the family down a frame after
     * it had been put up, and the rail beside an open project page fell back to
     * showing the news.
     */
    fun switch(surface: SurfaceId, family: FamilyId) {
        if (family == FamilyId.GENERAL) {
            reset(surface)
            return
        }
        active[surface] = family
        claims[surface] = (claims[surface] ?: 0) + 1
    }

    /**
     * Gives up one claim, and puts [surface] back to its general family with the
     * last of them.
     */
    fun reset(surface: SurfaceId) {
        val remaining = (claims[surface] ?: 0) - 1
        if (remaining > 0) {
            claims[surface] = remaining
            return
        }
        claims.remove(surface)
        active.remove(surface)
    }

    /** Every surface currently off its general family, for diagnostics and the editor. */
    fun switched(): Map<SurfaceId, FamilyId> = active.toMap()
}

/**
 * The app's family state. Static: the holder reference is fixed at startup and
 * the reactivity lives inside it, not in the Local.
 *
 * The default is a real holder rather than an error, because a surface rendered
 * outside the app -- a render probe, a test, a future TUI -- has a perfectly good
 * answer without one, which is that everything shows its general family.
 */
val LocalSurfaceFamilies: ProvidableCompositionLocal<SurfaceFamilies> =
    staticCompositionLocalOf { SurfaceFamilies() }

/**
 * Per-surface family override for a subtree, which the editor provides to work on
 * a family the app is not currently showing.
 *
 * Without it the only way to edit the project-view rail would be to navigate to a
 * project first, which makes arranging one family a matter of getting the app
 * into the right state instead of choosing it.
 */
val LocalFamilyOverrides: ProvidableCompositionLocal<Map<SurfaceId, FamilyId>> =
    compositionLocalOf { emptyMap() }

/** The family [surface] is showing here: the editor's override if one is set, else the live one. */
@Composable
fun activeFamilyOf(surface: SurfaceId): FamilyId =
    LocalFamilyOverrides.current[surface] ?: LocalSurfaceFamilies.current.activeIn(surface)

/**
 * Renders whichever family [surface] is showing, on the plane that family wears.
 *
 * The host branches on the id [content] receives, because the slots differ
 * between families and the surface owns their geometry: a family is not a
 * rearrangement of one set of slots, it is a different set, and the code that
 * lays them out is part of what the family is.
 *
 * Wrapping is done here and not left to each caller so a family's plane cannot be
 * forgotten in one branch and drawn in another.
 */
@Composable
fun SurfaceFamilyHost(
    surface: SurfaceId,
    content: @Composable (FamilyId) -> Unit,
) {
    val family = activeFamilyOf(surface)
    val spec = LocalLayoutGraph.current.surfaces[surface]?.family(family)?.surface
    if (spec == null) {
        content(family)
    } else {
        LocalWidgetSurfaceRenderer.current(spec) { content(family) }
    }
}
