package hivens.widget.model

/**
 * The kinds of level in the tree a data source or a service lives at.
 *
 * Each level is a live object its owner creates and disposes: the app for [App], a
 * window for [Window], the composition drawn in a window for [Composition], an
 * entry of a history for [Page]. Whatever a level made is disposed with it.
 *
 * A level stands under its owner, whatever the owner's kind: a window the launcher
 * opened under the app, one a composition opened under that composition. Only [App]
 * has no owner and is the root of every tree. The order here is how levels usually
 * stand and decides nothing.
 */
enum class ScopeLevel {
    App,
    Window,
    Composition,
    Page,
}
