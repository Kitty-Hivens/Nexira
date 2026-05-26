package hivens.ui.notifications

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

// Ordering matters: NotificationGroup.severity is `events.maxOf { it.severity }`,
// so a past Critical event stays visually marked as Critical even after a later
// Info arrives -- prevents accidentally hiding hard failures that scrolled past.
enum class Severity {
    Info,
    Progress,
    Success,
    Warn,
    Critical;

    /** Null = sticky until manually dismissed or the next event in the group supersedes. */
    val autoDismissAfter: Duration?
        get() = when (this) {
            Info     -> 5.seconds
            Success  -> 4.seconds
            Progress -> null
            Warn     -> 30.seconds
            Critical -> null
        }
}
