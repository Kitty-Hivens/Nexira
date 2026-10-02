package hivens.ui.surface

/**
 * What a surface is, which decides how it takes its colour.
 *
 * A word names a thing in the interface, never a depth: depth is worked out from the
 * plane the surface sits on, so the same word lands right inside anything and on
 * either theme. A screen that needs a kind of surface this list does not have asks
 * for it here rather than drawing its own.
 */
enum class SurfaceKind {
    /** The window ground. Step 0. */
    Page,

    /** The persistent frame: the rail, the title bar. Glass over what is behind it, no tint of its own. */
    Chrome,

    /** A region holding a group: a settings section, a side panel. One step above its parent. */
    Panel,

    /** A self-contained item of a collection: a pack, a news entry. One step above its parent. */
    Card,

    /**
     * Where the user types or picks: a text field, a search box, a select. One step
     * toward the page from its parent, which reads as a recess on a dark theme and as
     * the familiar light field inside a grey block on a light one.
     */
    Field,

    /** Transient and above everything: a menu, a tooltip, a list that drops down. The top step, opaque, with a shadow. */
    Popup,

    /** A modal window. As [Popup]; the scrim behind it is the caller's, in the page colour. */
    Dialog,

    /** A transient message. As [Popup]. */
    Notice,
}
