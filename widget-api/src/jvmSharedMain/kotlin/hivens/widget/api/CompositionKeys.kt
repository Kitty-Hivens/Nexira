package hivens.widget.api

import hivens.widget.model.CommandKey
import hivens.widget.model.CompositionName
import hivens.widget.model.ConfigId
import hivens.widget.model.ScopeLevel
import hivens.widget.model.SourceKey

/**
 * What every composition answers for the widgets drawn in it, whoever made it: the config it draws and the windows it
 * owns. The kernel's own keys rather than a module's, since every composition draws a config and can own a window, and
 * they name configs and compositions by id, so no widget needs a type of the host's to reach one.
 */
object CompositionKeys {
    /** The config the composition draws now, which a widget's condition of where it is shown is read against. */
    val Config = SourceKey<ConfigId>("composition.config", ScopeLevel.Composition)

    /** The compositions shown in a window this composition opened, which go when it goes. */
    val Windows = SourceKey<Set<CompositionName>>("nav.windows", ScopeLevel.Composition)

    /**
     * Shows a composition of the document in a window this composition owns, a picker say, or brings the one it is
     * shown in to the front. The window goes when this composition goes, so nothing it opened is left behind with
     * nowhere to answer. One the document does not have opens nothing. A host with one window shows it over that
     * window instead. A window apart from any composition is [WindowKeys.Show]'s.
     */
    val ShowWindow = CommandKey<CompositionName>("nav.window.show", ScopeLevel.Composition)

    /** Closes a window this composition opened. */
    val CloseWindow = CommandKey<CompositionName>("nav.window.close", ScopeLevel.Composition)
}
