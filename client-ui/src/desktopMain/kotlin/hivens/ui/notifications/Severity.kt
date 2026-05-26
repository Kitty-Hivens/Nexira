package hivens.ui.notifications

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Severity of a [NotificationEvent]. Drives two things:
 *
 *  - Auto-dismiss policy on the parent [NotificationGroup]. Critical
 *    notifications never auto-dismiss; progress and warning ones stay
 *    until the next event in the group overrides them; info and
 *    success auto-dismiss after a short window.
 *  - Visual accent on the rendered card (slim left bar). Default is
 *    a passive surface with no accent; warnings get a muted accent,
 *    criticals get a pulsing accent.
 *
 * The enum order is the visual severity ladder -- if a group has
 * mixed severities (Info event followed by Critical), the group's
 * effective severity is `events.maxOf { it.severity }`.
 */
enum class Severity {
    /** Background info; quietly auto-dismisses after a short window. */
    Info,

    /** Long-running progress; sticky until the group ends in Success / Warn / Critical. */
    Progress,

    /** Operation finished cleanly; auto-dismisses after a short window. */
    Success,

    /** Recoverable issue; sticky until acknowledged or superseded by the next event. */
    Warn,

    /** User must act; never auto-dismisses, accent pulses. */
    Critical;

    /**
     * Time after which a single-event group at this severity should
     * disappear from the visible stack. Null = sticky (user dismisses
     * or the next event in the group supersedes).
     */
    val autoDismissAfter: Duration?
        get() = when (this) {
            Info     -> 5.seconds
            Success  -> 4.seconds
            Progress -> null
            Warn     -> 30.seconds
            Critical -> null
        }
}
