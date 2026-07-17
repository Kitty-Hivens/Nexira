package hivens.core.update

/**
 * Pack version comparison. Mirrors the mirror's `domain/version.rs` byte for
 * byte so client and server agree on "is there a newer build?": numeric-tuple
 * comparison with missing trailing segments treated as 0. Plain string sort is
 * wrong (`.10` would sort before `.2`), so both sides must use this.
 *
 * A segment that is not a plain integer degrades to 0 (same as the mirror's
 * `parse::<u64>().unwrap_or(0)`), so the canonical `YYYY.MM.DD[.N]` form orders
 * correctly and a malformed version still compares rather than throwing.
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
