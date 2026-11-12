package hivens.widget.model

/**
 * The levels of the tree a data source or a service lives at, outermost first.
 *
 * Each level is a live object its owner creates and disposes: the app for [App], a
 * window for [Window], the composition drawn in a window for [Composition], an
 * entry of a history for [Page]. Whatever a level made is disposed with it. The
 * order is the nesting, so a level only sits inside one declared before it.
 */
enum class ScopeLevel {
    App,
    Window,
    Composition,
    Page,
}
