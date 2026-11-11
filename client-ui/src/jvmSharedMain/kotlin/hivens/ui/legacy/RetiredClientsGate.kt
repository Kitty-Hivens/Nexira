package hivens.ui.legacy

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The one place that says whether the leftover-clients surface is open.
 *
 * A gate rather than screen state, for the same reason the two-factor prompt is
 * one: what opens it is a notification action, which fires from outside any
 * composition and has no screen to navigate. The shell renders the host; anything
 * that can reach this can ask for it.
 *
 * Deliberately not a [hivens.ui.Screen]. This is a chore with an end -- once the
 * player has dealt with what the retired path left, there is nothing to come back
 * to -- and a route in the back stack would outlive the reason it exists.
 */
class RetiredClientsGate {
    private val _open = MutableStateFlow(false)
    val open: StateFlow<Boolean> = _open.asStateFlow()

    fun show() {
        _open.value = true
    }

    fun dismiss() {
        _open.value = false
    }
}
