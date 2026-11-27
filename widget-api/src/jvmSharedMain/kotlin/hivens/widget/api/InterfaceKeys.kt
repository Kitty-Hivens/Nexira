package hivens.widget.api

import hivens.widget.model.ScopeLevel
import hivens.widget.model.SourceKey

/** What the host says of the interface as a whole, for the modules that keep something across its starts. */
object InterfaceKeys {
    /**
     * How often the interface started over after a crash this run. A module that keeps what was shown can step it
     * back off what the interface may have crashed on before it is drawn again.
     */
    val Restarts = SourceKey<InterfaceRestarts>("interface.restarts", ScopeLevel.App)
}

/**
 * The interface's starts over after a crash this run: [count] of them, and whether the last came [again], before the
 * interface had come up and stayed up since the one before.
 */
data class InterfaceRestarts(val count: Int = 0, val again: Boolean = false)
