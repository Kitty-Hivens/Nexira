package hivens.launcher.update

import hivens.core.data.ContentToggle
import hivens.core.data.PackInstance

/**
 * This record with the build [source] describes: which build of the pack is on
 * disk, what it placed there, and what it runs on. Everything else is the
 * player's, or the launcher's about the player, and stays as this record has it.
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
internal fun PackInstance.withBuildOf(source: PackInstance): PackInstance = copy(
    packRef = packRef.copy(version = source.packRef.version),
    pinnedPackVersion = source.pinnedPackVersion,
    installedBuildKey = source.installedBuildKey,
    installedManifest = source.installedManifest,
    cachedManifest = source.cachedManifest,
    optionalContent = mergeToggles(source.optionalContent, optionalContent),
)

private fun mergeToggles(base: List<ContentToggle>, over: List<ContentToggle>): List<ContentToggle> {
    val chosen = over.associateBy { it.entryId }
    val merged = base.map { chosen[it.entryId] ?: it }
    val known = base.mapTo(HashSet()) { it.entryId }
    return merged + over.filterNot { it.entryId in known }
}
