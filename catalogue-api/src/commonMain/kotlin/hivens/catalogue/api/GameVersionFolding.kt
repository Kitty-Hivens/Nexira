package hivens.catalogue.api

// The game versions a project supports, folded the way the catalogue's own page folds
// them. Here rather than with a reader, because the rail, a version table and the
// chosen filters all fold, and three copies of the rule would drift apart.
//
// The rules, in the order they matter:
//
//  * Releases group by major (`1.21`), never across one.
//  * A major whose every published minor is supported collapses to `1.21.x`.
//  * A partial major becomes a range inside itself, `1.21.1-1.21.4`.
//  * A single minor is just itself, and `.0` is dropped, so `1.21.0` reads `1.21`.
//  * Anything older than the release line groups by adjacency in the catalogue's own
//    list, and the whole spans get the names people use for them.
//  * Snapshots are shown only when there are no releases at all, because a project
//    that supports a release supports the snapshots behind it. The exception is a
//    snapshot newer than the newest supported release, which gets its own chip first.

/** [groupGameVersions] reduced to what a chip shows. */
fun foldGameVersions(supported: List<String>, all: List<GameVersionTag>): List<String> =
    groupGameVersions(supported, all).map { it.label }

/**
 * [groupGameVersions], or each version a chip of its own when [all] is not to hand.
 * Folding needs the catalogue's list for the order only, and a project whose list
 * could not be read still names the versions it runs on.
 */
fun gameVersionChips(supported: List<String>, all: List<GameVersionTag>): List<GameVersionGroup> =
    groupGameVersions(supported, all).ifEmpty { supported.map { GameVersionGroup(it, listOf(it)) } }

/** The chips [supported] folds into against the catalogue's whole list [all], newest first. */
fun groupGameVersions(supported: List<String>, all: List<GameVersionTag>): List<GameVersionGroup> {
    if (supported.isEmpty() || all.isEmpty()) return emptyList()

    val order = all.withIndex().associate { (i, v) -> v.version to i }
    val ordered = supported.filter { it in order }.sortedBy { order.getValue(it) }
    if (ordered.isEmpty()) return emptyList()

    val releases = all.filter { it.versionType == GameVersionTag.RELEASE }
    val snapshots = all.filter { it.versionType == GameVersionTag.SNAPSHOT }
    val legacy = all.filter { it.versionType != GameVersionTag.RELEASE && it.versionType != GameVersionTag.SNAPSHOT }

    val releaseNames = releases.mapTo(HashSet()) { it.version }
    val supportedReleases = ordered.filter { it in releaseNames }

    val releaseRanges = majorRanges(supported = supportedReleases, published = releases.map { it.version })

    val legacyNames = legacy.mapTo(HashSet()) { it.version }
    val legacyRanges = adjacentRanges(ordered.filter { it in legacyNames }, legacy)

    val out = mutableListOf<GameVersionGroup>()

    if (releaseRanges.isEmpty()) {
        // Nothing but snapshots. A handful reads better one per chip than folded into
        // a range whose ends nobody recognises.
        val supportedSnapshots = ordered.filter { name -> snapshots.any { it.version == name } }
        out += if (supportedSnapshots.size > 3) {
            adjacentRanges(supportedSnapshots, snapshots)
        } else {
            supportedSnapshots.map { GameVersionGroup(it, listOf(it)) }
        }
    } else {
        out += releaseRanges
    }
    out += legacyRanges

    if (releaseRanges.isNotEmpty()) {
        newestForwardSnapshot(ordered, supportedReleases, releases, snapshots)
            ?.let { out.add(0, GameVersionGroup(it, listOf(it))) }
    }
    return out
}

/**
 * Release ranges, one per major, newest major first. [published] is every release the
 * catalogue lists, which is what decides whether a major is covered whole.
 */
private fun majorRanges(supported: List<String>, published: List<String>): List<GameVersionGroup> {
    val supportedByMajor = groupByMajor(supported, consecutive = true)
    val publishedByMajor = groupByMajor(published, consecutive = false)

    return supportedByMajor.map { (major, minors) ->
        val members = minors.map { minorLabel(major, it) }
        if (minors.size == 1) return@map GameVersionGroup(members.first(), members)
        val whole = publishedByMajor.firstOrNull { it.first == major }?.second
        val label = if (whole != null && whole == minors) "$major.x" else "${members.first()}-${members.last()}"
        GameVersionGroup(label, members)
    }
}

private val MC_VERSION = Regex("""^(\d+\.\d+)(\.\d+)?$""")

/**
 * Versions bucketed by major, each bucket's minors ascending. With [consecutive] a gap
 * starts a new bucket, so a project that skipped 1.21.2 does not claim the range
 * across it. Walked oldest first and flipped at the end, because the catalogue's list
 * runs newest first and a range reads the other way.
 */
private fun groupByMajor(versions: List<String>, consecutive: Boolean): List<Pair<String, List<Int>>> {
    val buckets = mutableListOf<Pair<String, MutableList<Int>>>()
    for (version in versions.asReversed()) {
        val match = MC_VERSION.matchEntire(version) ?: continue
        val major = match.groupValues[1]
        val minor = match.groupValues[2].removePrefix(".").toIntOrNull() ?: 0
        val open = buckets.lastOrNull { it.first == major && (!consecutive || it.second.last() == minor - 1) }
        if (open != null) open.second.add(minor) else buckets.add(major to mutableListOf(minor))
    }
    return buckets.asReversed().map { it.first to it.second.toList() }
}

/** `1.21.0` is written `1.21`, because that is what the release was called. */
private fun minorLabel(major: String, minor: Int): String = if (minor == 0) major else "$major.$minor"

/** Runs of versions adjacent in [reference], for the lines that have no major and minor to group by. */
private fun adjacentRanges(versions: List<String>, reference: List<GameVersionTag>): List<GameVersionGroup> {
    if (versions.isEmpty()) return emptyList()
    val index = reference.withIndex().associate { (i, v) -> v.version to i }
    val sorted = versions.filter { it in index }.sortedBy { index.getValue(it) }
    if (sorted.isEmpty()) return emptyList()

    val out = mutableListOf<GameVersionGroup>()
    var start = 0
    for (i in 1..sorted.size) {
        val broken = i == sorted.size || index.getValue(sorted[i]) != index.getValue(sorted[i - 1]) + 1
        if (!broken) continue
        val run = sorted.subList(start, i)
        // The reference runs newest first, so the run reads back to front.
        out += GameVersionGroup(namedSpan("${run.last()}-${run.first()}"), run.toList())
        start = i
    }
    return out
}

private fun namedSpan(range: String): String = when (range) {
    "rd-132211-b1.8.1" -> "all legacy versions"
    "a1.0.4-b1.8.1" -> "all alpha and beta versions"
    "a1.0.4-a1.2.6" -> "all alpha versions"
    "b1.0-b1.8.1" -> "all beta versions"
    "rd-132211-inf20100618" -> "all pre-alpha versions"
    else -> range.split('-').let { if (it.size == 2 && it[0] == it[1]) it[0] else range }
}

/**
 * The one snapshot worth showing beside releases: the newest supported one that came
 * out after the newest supported release. An older one is behind a release the
 * project already lists.
 */
private fun newestForwardSnapshot(
    ordered: List<String>,
    supportedReleases: List<String>,
    releases: List<GameVersionTag>,
    snapshots: List<GameVersionTag>,
): String? {
    val newestReleaseDate = supportedReleases.firstOrNull()?.let { name -> releases.firstOrNull { it.version == name }?.date }
    return ordered.firstOrNull { name ->
        val snapshot = snapshots.firstOrNull { it.version == name } ?: return@firstOrNull false
        val date = snapshot.date
        newestReleaseDate == null || (date != null && date > newestReleaseDate)
    }
}
