package hivens.core.update

/**
 * Result of checking whether a pack instance can move to another build. Pure
 * data produced by the update driver; no IO. The UI renders it (badge, preview)
 * and the apply step re-derives its own plan under a lock rather than trusting
 * this snapshot.
 */
sealed interface UpdateCheck {
    /** The installed build is already the latest the mirror offers. */
    data object UpToDate : UpdateCheck

    /**
     * A different build is available (newer for an update, older for a rollback).
     * [compat] grades how structural the change is (green = safe re-sync, amber =
     * snapshot first).
     *
     * [plan] is the three-way reconcile against the current on-disk state, and it
     * is null when the source cannot answer without being handed the whole pack.
     * A mirror build lists its files with hashes, so the plan is free; a Modrinth
     * version does not, and computing one would mean downloading the archive to
     * decide whether to offer a download. Null is "not computed", which is not
     * the same as "nothing changes" -- an empty plan means the version label
     * moved and no file did, and applying it just advances the recorded version.
     */
    data class Available(
        val fromVersion: String?,
        val toVersion: String,
        val direction: UpdateDirection,
        val compat: CompatChange,
        val plan: UpdatePlan?,
    ) : UpdateCheck
}

/**
 * Which way a build move goes. Detection is label inequality, so "a different
 * build is current" covers a mirror-side rollback of latest just as much as a
 * release -- without this the UI announces both as an update and can point the
 * user at a build older than the one they run.
 *
 * Ranked by the mirror's publish order (its listing is canonical, newest first),
 * never by comparing version tuples: a channel build's `SNAPSHOT-` prefix parses
 * to zero and would misrank against any release.
 */
enum class UpdateDirection {
    Newer,
    Older,

    /** No baseline, or a build the listing does not carry (retired, or offline). */
    Unknown,
}

/**
 * Outcome of applying an update or version switch. Failures (network, sha1
 * mismatch, IO) throw rather than returning a variant, consistent with the
 * fail-loud sync path; callers catch and surface them.
 */
sealed interface UpdateOutcome {
    /** The plan was applied and the instance committed at [toVersion]. */
    data class Applied(
        val toVersion: String,
        val compat: CompatChange,
        val plan: UpdatePlan,
    ) : UpdateOutcome

    /** The requested target equals the installed build; nothing was fetched or written. */
    data object AlreadyCurrent : UpdateOutcome
}
