package hivens.core.smrt

import hivens.core.api.dto.smrt.SmrtAssetEntry
import hivens.core.update.PackBuild
import hivens.core.api.dto.smrt.SmrtModEntry
import hivens.core.api.dto.smrt.SmrtPackManifest

/** How one entry differs between two builds. */
enum class DiffKind { Added, Removed, Updated }

/**
 * One content difference. [from] is the base build's entry (null for [DiffKind.Added]),
 * [to] the target build's (null for [DiffKind.Removed]); [DiffKind.Updated] carries both,
 * so a consumer can show old -> new size, path, or source.
 */
data class DiffEntry<T>(
    val kind: DiffKind,
    val from: T?,
    val to: T?,
)

/** A pack-level field that changed between two builds. */
data class PackFieldChange(val from: String, val to: String)

/**
 * Client-side file diff between two pack builds -- the mirror stores every
 * historical manifest but deliberately serves no diff endpoint (ADR 0002), so
 * "what changed in this build" is computed from two manifests here.
 *
 * Mod identity is [SmrtModEntry.stableKey] (slug -> modrinth project -> filename)
 * with a second pairing pass over the leftovers by normalized filename stem:
 * slug-less `smrt_cache` mods rename on every version bump, and without the stem
 * pass each bump would read as a remove plus an unrelated add. Asset identity is
 * the destination path. Equal NON-NULL [SmrtPackManifest.fingerprint]s short-cut
 * the whole file comparison ("content identical"); null fingerprints (pre-field
 * builds) never match anything, including each other.
 */
data class PackVersionDiff(
    val mods: List<DiffEntry<SmrtModEntry>>,
    val assets: List<DiffEntry<SmrtAssetEntry>>,
    val minecraft: PackFieldChange?,
    val loader: PackFieldChange?,
    val java: PackFieldChange?,
    val channel: PackFieldChange?,
    /** True when the two builds ship byte-identical content (by fingerprint or by comparison). */
    val identicalContent: Boolean,
) {
    val hasFileChanges: Boolean get() = mods.isNotEmpty() || assets.isNotEmpty()

    companion object {
        fun compute(from: SmrtPackManifest, to: SmrtPackManifest): PackVersionDiff {
            val fingerprintMatch = from.fingerprint != null && from.fingerprint == to.fingerprint
            val mods = if (fingerprintMatch) emptyList() else diffMods(from.mods, to.mods)
            val assets = if (fingerprintMatch) emptyList() else diffAssets(from.assets, to.assets)
            return PackVersionDiff(
                mods = mods,
                assets = assets,
                minecraft = fieldChange(from.minecraft.version, to.minecraft.version),
                loader = fieldChange(loaderLabel(from), loaderLabel(to)),
                java = fieldChange(from.java.major.toString(), to.java.major.toString()),
                channel = fieldChange(from.versionChannel.wire, to.versionChannel.wire),
                identicalContent = fingerprintMatch || (mods.isEmpty() && assets.isEmpty()),
            )
        }

        private fun loaderLabel(m: SmrtPackManifest) = "${m.loader.name} ${m.loader.version}"

        private fun fieldChange(from: String, to: String): PackFieldChange? =
            if (from == to) null else PackFieldChange(from, to)

        private fun diffMods(from: List<SmrtModEntry>, to: List<SmrtModEntry>): List<DiffEntry<SmrtModEntry>> {
            val fromByKey = from.associateBy { it.stableKey }
            val toByKey = to.associateBy { it.stableKey }

            val updated = ArrayList<DiffEntry<SmrtModEntry>>()
            val added = ArrayList<SmrtModEntry>()
            val removed = ArrayList<SmrtModEntry>()

            for (entry in to) {
                val old = fromByKey[entry.stableKey]
                when {
                    old == null -> added += entry
                    old.sha1.equals(entry.sha1, ignoreCase = true) && old.filename == entry.filename -> Unit
                    else -> updated += DiffEntry(DiffKind.Updated, old, entry)
                }
            }
            for (entry in from) {
                if (entry.stableKey !in toByKey) removed += entry
            }

            // Second pass: pair leftover adds/removes whose normalized filename stem
            // matches uniquely on both sides -- a slug-less mod whose versioned
            // filename changed. Ambiguous stems stay as separate add + remove.
            val addsByStem = added.groupBy { filenameStem(it.filename) }
            val removesByStem = removed.groupBy { filenameStem(it.filename) }
            val paired = HashSet<String>()
            for ((stem, adds) in addsByStem) {
                val removes = removesByStem[stem] ?: continue
                if (adds.size == 1 && removes.size == 1) {
                    updated += DiffEntry(DiffKind.Updated, removes.single(), adds.single())
                    paired += stem
                }
            }

            return buildList {
                addAll(updated)
                added.filterNot { filenameStem(it.filename) in paired }
                    .forEach { add(DiffEntry(DiffKind.Added, null, it)) }
                removed.filterNot { filenameStem(it.filename) in paired }
                    .forEach { add(DiffEntry(DiffKind.Removed, it, null)) }
            }.sortedWith(compareBy({ it.kind }, { labelOf(it).lowercase() }))
        }

        private fun diffAssets(from: List<SmrtAssetEntry>, to: List<SmrtAssetEntry>): List<DiffEntry<SmrtAssetEntry>> {
            val fromByDest = from.associateBy { it.dest }
            val toByDest = to.associateBy { it.dest }
            return buildList {
                for (entry in to) {
                    val old = fromByDest[entry.dest]
                    when {
                        old == null -> add(DiffEntry(DiffKind.Added, null, entry))
                        old.sha1.equals(entry.sha1, ignoreCase = true) -> Unit
                        else -> add(DiffEntry(DiffKind.Updated, old, entry))
                    }
                }
                for (entry in from) {
                    if (entry.dest !in toByDest) add(DiffEntry(DiffKind.Removed, entry, null))
                }
            }.sortedWith(compareBy({ it.kind }, { (it.to ?: it.from)?.dest?.lowercase() ?: "" }))
        }

        private fun labelOf(e: DiffEntry<SmrtModEntry>): String {
            val entry = e.to ?: e.from
            return entry?.display?.name ?: entry?.filename ?: ""
        }

        /**
         * Filename normalized down to the mod's identity: extension, `mc<version>`
         * marker and trailing version tokens stripped, lowercased. Mirrors the
         * scanner's pretty-name heuristic; only used to pair versioned renames.
         */
        internal fun filenameStem(filename: String): String =
            filename.removeSuffix(".jar").removeSuffix(".zip")
                .substringBefore("-mc")
                .substringBefore("_mc")
                .replace(Regex("[-_]v?\\d.*$"), "")
                .lowercase()
                .trim()
                .ifBlank { filename.lowercase() }
    }
}

/**
 * Collapse the build list into runs of consecutive builds shipping identical
 * content (equal non-null fingerprints): a version-label-only rebuild chain
 * renders as one group instead of N identical "changes". Builds without a
 * fingerprint never group. Order is preserved.
 */
fun groupRebuildRuns(builds: List<PackBuild>): List<List<PackBuild>> {
    val runs = ArrayList<MutableList<PackBuild>>()
    for (build in builds) {
        val current = runs.lastOrNull()
        if (current != null && build.fingerprint != null && build.fingerprint == current.last().fingerprint) {
            current += build
        } else {
            runs += mutableListOf(build)
        }
    }
    return runs
}
