package hivens.ui.utils

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Cross-cutting alert signal for the right-rail console widget.
 *
 * LaunchDriver writes the level on launch-error / abnormal-exit; the
 * widget observes it to decide whether to auto-expand and which accent
 * to tint the badge with. Kept independent of NotificationCenter so the
 * widget doesn't need notification-channel introspection just to know
 * "something noteworthy happened".
 *
 * Single Koin singleton. The state flow stays cheap to subscribe -- one
 * collector per console-widget instance, fired on level change only.
 */
enum class ConsoleAlertLevel { None, Warn, Critical }

class ConsoleAlertState {
    private val _level = MutableStateFlow(ConsoleAlertLevel.None)
    val level: StateFlow<ConsoleAlertLevel> = _level.asStateFlow()

    /**
     * Monotonic raise: only writes when the new level is more severe
     * than the current one. Otherwise a transient WARN would erase a
     * pending Critical between fires. The widget's [clear] is the only
     * way to drop the level back to None.
     */
    fun raise(newLevel: ConsoleAlertLevel) {
        if (newLevel.ordinal > _level.value.ordinal) {
            _level.value = newLevel
        }
    }

    fun clear() {
        _level.value = ConsoleAlertLevel.None
    }
}
