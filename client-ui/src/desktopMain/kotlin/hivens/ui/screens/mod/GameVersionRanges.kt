package hivens.ui.screens.mod

import hivens.core.api.dto.modrinth.ModrinthGameVersion

/**
 * The game versions a project supports, folded the way the catalogue's own page
 * folds them.
 *
 * This is a port, not an invention. The first pass here grouped runs of
 * consecutive releases out of the whole ordered list, which reads plausibly and is
 * wrong in two ways that show on screen: it produces ranges that cross a major
 * (`1.20.6-1.21.1`, which answers "does this run on 1.21" with a question), and it
 * has no form for "every 1.21 there is", which is the commonest case and the one a
 * reader most wants. Cloth Config came out as seven chips of arithmetic instead of
 * four names.
 *
 * The rules, in the order they matter:
 *
 *  * Releases group by MAJOR (`1.21`), never across one.
 *  * A major whose every published minor is supported collapses to `1.21.x`.
 *  * A partial major becomes a range inside itself, `1.21.1-1.21.4`.
 *  * A single minor is just itself, and `.0` is dropped, so `1.21.0` reads `1.21`.
 *  * Anything older than the release line (alpha, beta, the pre-classic names)
 *    groups by adjacency in the catalogue's own list, and the whole spans get the
 *    names people use for them rather than a range nobody reads.
 *  * Snapshots are shown only when there are no releases at all, because a project
 *    that supports a release supports the snapshots behind it. The one exception is
 *    a snapshot NEWER than the newest supported release, which is the project
 *    saying it is ready for what is coming; that one gets its own chip in front.
 */
/**
 * One chip, and the versions behind it.
 *
 * The label is a handle for a SET: `1.21.x` stands for every minor of that major
 * the project supports. A filter built on the label alone could not say which
 * builds it means, which is why the catalogue's own page carries the group and
 * formats the label out of it rather than the other way round.
 */
data class GameVersionGroup(val label: String, val versions: List<String>)

/** [groupGameVersions] reduced to what a chip shows. */
fun foldGameVersions(supported: List<String>, all: List<ModrinthGameVersion>): List<String> =
    groupGameVersions(supported, all).map { it.label }

fun groupGameVersions(supported: List<String>, all: List<ModrinthGameVersion>): List<GameVersionGroup> {
    if (supported.isEmpty() || all.isEmpty()) return emptyList()

    val order = all.withIndex().associate { (i, v) -> v.version to i }
    val ordered = supported.filter { it in order }.sortedBy { order.getValue(it) }
    if (ordered.isEmpty()) return emptyList()

    val releases = all.filter { it.versionType == ModrinthGameVersion.RELEASE }
    val snapshots = all.filter { it.versionType == ModrinthGameVersion.SNAPSHOT }
    val legacy = all.filter {
        it.versionType != ModrinthGameVersion.RELEASE && it.versionType != ModrinthGameVersion.SNAPSHOT
    }

    val releaseNames = releases.mapTo(HashSet()) { it.version }
    val supportedReleases = ordered.filter { it in releaseNames }

    val releaseRanges = majorRanges(
        supported = supportedReleases,
        published = releases.map { it.version },
    )

    val legacyNames = legacy.mapTo(HashSet()) { it.version }
    val legacyRanges = adjacentRanges(ordered.filter { it in legacyNames }, legacy)

    val out = mutableListOf<GameVersionGroup>()

    if (releaseRanges.isEmpty()) {
        // Nothing but snapshots. A handful reads better one per chip than folded
        // into a range whose ends nobody recognises.
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
 * Release ranges, one per major, newest major first.
 *
 * [published] is every release the catalogue lists, which is what decides whether
 * a major is covered whole: `1.21.x` is a claim about the major and can only be
 * made against the full list, not against the project's own.
 */
private fun majorRanges(supported: List<String>, published: List<String>): List<GameVersionGroup> {
    val supportedByMajor = groupByMajor(supported, consecutive = true)
    val publishedByMajor = groupByMajor(published, consecutive = false)

    return supportedByMajor.map { (major, minors) ->
        val members = minors.map { minorLabel(major, it) }
        if (minors.size == 1) return@map GameVersionGroup(members.first(), members)
        val whole = publishedByMajor.firstOrNull { it.first == major }?.second
        val label = if (whole != null && whole == minors) {
            "$major.x"
        } else {
            "${members.first()}-${members.last()}"
        }
        GameVersionGroup(label, members)
    }
}

private val MC_VERSION = Regex("""^(\d+\.\d+)(\.\d+)?$""")

/**
 * Versions bucketed by major, each bucket's minors ascending.
 *
 * With [consecutive] a gap starts a new bucket, so a project that skipped 1.21.2
 * does not claim the range across it. Without it every minor of a major lands in
 * one bucket, which is what the "is this major covered whole" question needs.
 *
 * Walked oldest-first and flipped at the end, because the catalogue's list runs
 * newest-first and a range reads the other way.
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

/**
 * Runs of versions adjacent in [reference], for the lines that have no major and
 * minor to group by.
 *
 * The whole spans have names. "a1.0.4-b1.8.1" is a string nobody reads; "all alpha
 * and beta versions" is what it means, and a project that supports the lot is
 * saying something simple.
 */
private fun adjacentRanges(versions: List<String>, reference: List<ModrinthGameVersion>): List<GameVersionGroup> {
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
        // The reference runs newest-first, so the run reads back to front.
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
 * The one snapshot worth showing beside releases: the newest supported one that
 * came out AFTER the newest supported release.
 *
 * A snapshot older than that is behind a release the project already lists, so
 * naming it adds a chip and no information.
 */
private fun newestForwardSnapshot(
    ordered: List<String>,
    supportedReleases: List<String>,
    releases: List<ModrinthGameVersion>,
    snapshots: List<ModrinthGameVersion>,
): String? {
    val newestReleaseDate = supportedReleases.firstOrNull()
        ?.let { name -> releases.firstOrNull { it.version == name }?.date }
    return ordered.firstOrNull { name ->
        val snapshot = snapshots.firstOrNull { it.version == name } ?: return@firstOrNull false
        val date = snapshot.date
        newestReleaseDate == null || (date != null && date > newestReleaseDate)
    }
}
