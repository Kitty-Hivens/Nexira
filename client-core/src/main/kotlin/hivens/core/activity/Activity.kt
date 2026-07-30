package hivens.core.activity

/**
 * What the launcher is doing. Drives the glyph and, more importantly, how the
 * measure reads: [Game] has no fraction to show and is narrated by elapsed time
 * from [Activity.startedAtMillis], while the rest carry a done/total.
 */
enum class ActivityKind { Install, Update, Sync, Repair, Launch, Game }

/**
 * A control an activity offers. Deliberately not a label: the frontend maps
 * these onto localised strings, so the registry stays free of i18n and of the
 * UI module.
 */
enum class ActivityAction { Cancel, Pause, Stop }

sealed interface ActivityPhase {
    /**
     * In flight. [total] at or below zero means the size is not known yet, which
     * the frontend renders as an indeterminate measure rather than as zero
     * percent. [detail] is machine-originated text (a filename, a path) and is
     * redacted on the way into the registry.
     */
    data class Running(val done: Long, val total: Long, val detail: String? = null) : ActivityPhase

    data object Succeeded : ActivityPhase

    /** [reason] is an exception message and is redacted on the way in. */
    data class Failed(val reason: String) : ActivityPhase

    data object Cancelled : ActivityPhase
}

val ActivityPhase.isTerminal: Boolean get() = this !is ActivityPhase.Running

/**
 * One thing the launcher is doing, or has just finished doing.
 *
 * [key] is the identity across reports -- an advancing download re-reports under
 * the same key rather than appending, which is what lets the registry conflate a
 * burst of progress into one entry.
 */
data class Activity(
    val key: String,
    val kind: ActivityKind,
    val title: String,
    val iconUrl: String? = null,
    val phase: ActivityPhase,
    val startedAtMillis: Long,
    val updatedAtMillis: Long,
    val actions: Set<ActivityAction> = emptySet(),
)
