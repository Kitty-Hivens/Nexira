package hivens.widget.api

import hivens.widget.model.CommandKey
import hivens.widget.model.CompositionName
import hivens.widget.model.SourceKey

/**
 * What every host answers about the windows a composition of the document is shown in apart from the one it starts
 * in. The kernel's own keys, at the app level, since a window outlives what opened it.
 */
object WindowKeys {
    /** The compositions shown in a window of their own now, opened by [Show]. */
    val Open = SourceKey<Set<CompositionName>>("windows.open")

    /**
     * Shows a composition of the document in a window of its own, or brings the one it is shown in to the front. One the
     * document does not have opens nothing. A host that has one window, a phone, shows it over that window instead.
     */
    val Show = CommandKey<CompositionName>("windows.show")

    /** Closes the window a composition is shown in, with what it holds. */
    val Close = CommandKey<CompositionName>("windows.close")
}
