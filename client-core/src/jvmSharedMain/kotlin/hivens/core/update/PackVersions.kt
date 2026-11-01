package hivens.core.update

/**
 * Numeric-tuple pack version comparison, same arithmetic as the mirror's
 * `domain/version.rs` (missing trailing segments are 0, `.10` sorts after `.2`,
 * a non-integer segment degrades to 0 like `parse::<u64>().unwrap_or(0)`).
 *
 * Valid ONLY within one channel's numeric versions. A channel build's
 * `SNAPSHOT-` prefix parses to 0, so tuples misrank it against any release --
 * update detection compares labels for inequality instead, and build ordering
 * comes from the server listing (publish date), never from this comparator.
 */
fun packVersionTuple(version: String): List<Long> =
    version.split('.').map { it.toLongOrNull() ?: 0L }

/**
 * Compare two pack versions per the mirror rules. `2026.05.22` equals
 * `2026.05.22.0`, is less than `2026.05.22.1`, and `2026.05.22.10` sorts after
 * `2026.05.22.2`. Returns the usual negative / zero / positive.
 */
fun comparePackVersions(a: String, b: String): Int {
    val at = packVersionTuple(a)
    val bt = packVersionTuple(b)
    val n = maxOf(at.size, bt.size)
    for (i in 0 until n) {
        val av = at.getOrElse(i) { 0L }
        val bv = bt.getOrElse(i) { 0L }
        val c = av.compareTo(bv)
        if (c != 0) return c
    }
    return 0
}

/** True when [candidate] is a strictly newer build than [installed]. */
fun isNewerPackVersion(candidate: String, installed: String): Boolean =
    comparePackVersions(candidate, installed) > 0
