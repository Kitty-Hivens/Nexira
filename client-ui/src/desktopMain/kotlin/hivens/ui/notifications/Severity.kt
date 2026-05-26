package hivens.ui.notifications

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

// Color/urgency band. Orthogonal to Kind, which controls lifecycle.
// Ordering matters: NotificationGroup.severity is `events.maxOf { it.severity }`,
// so a past Critical stays visually marked even after a later Info -- the
// loud severity never silently drops out of the user's view.
enum class Severity {
    Info,
    Success,
    Warn,
    Critical;

    // Default OneShot window. Kind.OneShot consults this; sticky kinds
    // (Progress, Sticky, ActionRequired) ignore it entirely.
    val defaultDuration: Duration
        get() = when (this) {
            Info     -> 5.seconds
            Success  -> 4.seconds
            Warn     -> 30.seconds
            Critical -> 30.seconds
        }
}
