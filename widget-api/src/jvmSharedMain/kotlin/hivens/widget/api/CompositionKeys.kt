package hivens.widget.api

import hivens.widget.model.CommandKey
import hivens.widget.model.ScopeLevel

/**
 * What every composition answers for the widgets drawn in it, whoever made it: the commands that
 * move what it shows. The kernel's own keys rather than a module's, since every composition has a
 * history, and they name configs by id, so no widget needs a type of the host's to reach one.
 */
object CompositionKeys {
    /** Steps back one entry of the composition's history. At its root it moves nothing. */
    val Back = CommandKey<Unit>("nav.back", ScopeLevel.Composition)

    /**
     * Shows the config of this id, keeping the way back. An id the composition has no config for
     * shows nothing new, and the history stays where it was.
     */
    val ShowConfig = CommandKey<String>("nav.show", ScopeLevel.Composition)
}
