package hivens.core.activity

/**
 * What the launcher is doing. Every kind is work in progress with an end; a
 * running game is deliberately not one of them -- that is a state the user can
 * see out of the window, and it has its own controls on the pack surface.
 */
enum class ActivityKind { Install, Update, Sync, Repair, Launch }

/**
 * A control an activity offers. Deliberately not a label: the frontend maps
 * these onto localised strings, so the registry stays free of i18n and of the
 * UI module. Stopping a running game is not here, because a running game is not
 * an activity -- see [ActivityKind].
 */
enum class ActivityAction {
    Cancel,
    Pause,

    /**
     * Take the entry off the surface. Unlike the others this is not something a
     * source can do -- it acts on the record, not the work -- so the surface adds
     * it rather than a driver advertising it. A failure never leaves by age, which
     * makes this the only way one goes.
     */
    Dismiss,
}

sealed interface ActivityPhase {
    /**
     * In flight. [total] at or below zero means the size is not known yet, which
     * the frontend renders as an indeterminate measure rather than as zero
     * percent. [detail] is machine-originated text (a filename, a path) and is
     * redacted on the way into the registry.
     */
    data class Running(val done: Long, val total: Long, val detail: String? = null) : ActivityPhase

    data object Succeeded : ActivityPhase

    /**
     * [reason] is an exception message and is redacted on the way in. Null when
     * the source genuinely has none to give -- the sync service reports a count
     * of failures, not a cause -- so the frontend can say "failed" in its own
     * words instead of the driver inventing a sentence outside the string table.
     */
    data class Failed(val reason: String? = null) : ActivityPhase

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
