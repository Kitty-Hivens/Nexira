package hivens.launcher.record

import hivens.core.data.ContentToggle
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin

/**
 * This record with the build [source] describes: which build of the pack is on
 * disk, where that build came from, what it placed there, and what it runs on.
 * Everything else is the player's, or the launcher's about the player, and stays
 * as this record has it.
 *
 * Where the pack came from moves only with [provenance], for a record a reattach
 * captured. A reattach moves a detached pack from Local back onto its source in
 * the same apply that changes its files, so undoing it has to put the pack back on
 * Local too: rolled back with the version alone, it kept the source's reference
 * over the player's own mods, unbound and no longer offering a reattach. Any other
 * build takes the version only, so an update that began before a detach cannot
 * undo the detach when it commits or rolls back. The server binding moves with
 * [PackInstance.cachedManifest] either way.
 *
 * What a rollback writes. Writing the snapshot's record whole put back the whole
 * of the day it was taken: the playtime since, the notes, the name, the runtime
 * settings, all undone along with the build.
 *
 * Optional content is merged rather than taken from either side. The snapshot's
 * list holds the toggles of mods the undone build had dropped, which the rolled
 * back build has again, and the current list holds whatever the player switched
 * since. The current choice wins where both name the same entry.
 */
internal fun PackInstance.withBuildOf(source: PackInstance, provenance: Boolean = false): PackInstance = copy(
    packRef = if (provenance) source.packRef else packRef.copy(version = source.packRef.version),
    forkedFrom = if (provenance) source.forkedFrom else forkedFrom,
    pinnedPackVersion = source.pinnedPackVersion,
    installedBuildKey = source.installedBuildKey,
    installedManifest = source.installedManifest,
    // A pack detached since keeps launching unbound: the binding is the source's, and
    // only a rollback that also puts the source back may put it back.
    cachedManifest = if (!provenance && isDetached) source.cachedManifest?.copy(authRequirement = null) else source.cachedManifest,
    optionalContent = mergeToggles(source.optionalContent, optionalContent),
)

private fun mergeToggles(base: List<ContentToggle>, over: List<ContentToggle>): List<ContentToggle> {
    val chosen = over.associateBy { it.entryId }
    val merged = base.map { chosen[it.entryId] ?: it }
    val known = base.mapTo(HashSet()) { it.entryId }
    return merged + over.filterNot { it.entryId in known }
}

/** Whether this record is a pack detached from a source: Local, with the source it came from noted. */
private val PackInstance.isDetached: Boolean get() = packRef.origin == PackOrigin.Local && forkedFrom != null
