package hivens.ui.notifications

import kotlin.time.Duration

// Lifecycle band. Orthogonal to Severity. Decides whether the
// notification auto-dismisses and roughly how the renderer should treat
// it (Progress -> show indeterminate/animated UI, ActionRequired ->
// expect actions, Sticky -> stays until the user dismisses).
enum class Kind {
    OneShot,
    Progress,
    Sticky,
    ActionRequired;

    fun autoDismissAfter(severity: Severity): Duration? = when (this) {
        OneShot                              -> severity.defaultDuration
        Progress, Sticky, ActionRequired     -> null
    }
}
