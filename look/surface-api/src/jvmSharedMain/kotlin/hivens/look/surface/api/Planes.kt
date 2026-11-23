package hivens.look.surface.api

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * What a plane is, which decides what it is made of.
 *
 * A word names a thing in the interface, never a depth: depth is worked out from the plane the
 * surface sits on, so the same word lands right inside anything and on any look.
 */
enum class SurfaceKind {
    /** The window ground. */
    Page,

    /**
     * The persistent frame: the rail, the title bar. It frames what it holds and adds no depth to it.
     */
    Chrome,

    /** A region holding a group: a settings section, a side panel. */
    Panel,

    /** A self-contained item of a collection: a pack, a news entry. */
    Card,

    /** Where a person types or picks: a text field, a search box, a select. */
    Field,

    /** Transient and above everything: a menu, a tooltip, a list that drops down. */
    Popup,

    /** A modal window over the window. */
    Dialog,

    /** A transient message. */
    Notice,
}

/** What is behind a plane. */
enum class Backdrop {
    /** The look's own page. */
    Page,

    /** A person's picture. */
    Wallpaper,

    /** Another plane. */
    Plane,
}

/**
 * The plane content is drawn on: which step of the ladder it counts from ([step]), the colour actually
 * under the content ([color]), and what is behind it ([over]).
 *
 * Whatever draws a plane provides one to what it holds ([LocalPlane]), which is what makes depth
 * relative: a card is placed by whatever holds it, and a line or a wash is made from the colour it is
 * really on rather than from a guess.
 */
@Immutable
data class Plane(val step: Int, val color: Color, val over: Backdrop)

/** The plane content sits on. Null outside any plane, where the page is under it. */
val LocalPlane = compositionLocalOf<Plane?> { null }
