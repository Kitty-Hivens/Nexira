package hivens.launcher.sources

import hivens.core.data.PackInstance
import hivens.core.update.PackBuild
import hivens.core.update.PackSnapshot
import hivens.core.update.PackUpdater
import hivens.core.update.UpdateCheck
import hivens.core.update.UpdateOutcome
import hivens.launcher.update.SnapshotRollback
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Sends each instance to the updater of the source it came from.
 *
 * The composition root binds this as the single [PackUpdater], so nothing above
 * it knows there is more than one. That is the point: the alternative is every
 * caller asking where a pack came from and being widened by hand each time a
 * source learns to update, which is how the two guards this replaces came to exist.
 * The updaters are the sources' own, found through [sources] by the id the pack's
 * record carries.
 *
 * A pack with no source to ask, one made locally or synced from a game server, has
 * no version feed: it answers up to date, offers no builds and no snapshots, and the
 * surfaces that ask [handles] first never draw a control that would do nothing.
 *
 * A pack whose source cannot be asked offers no update either, and an update or a
 * switch asked of it all the same is refused rather than run by another source or
 * answered as if done: with [SourceMissingException] where the source's module is
 * not loaded, and with [SourceCannotException] where it is loaded and brings no
 * updater. Its snapshots stay its own, though: listing and rolling back one needs
 * nothing from the source, so [snapshots] does it.
 */
class RoutingPackUpdater(
    private val sources: SourceWorks,
    private val snapshots: SnapshotRollback? = null,
) : PackUpdater {

    private fun updaterFor(instance: PackInstance): PackUpdater? = sources.work(instance.packRef.origin)?.updater

    /** The updater for [instance], or the refusal when its source cannot update it. Null where the pack has no source. */
    private fun requireUpdater(instance: PackInstance): PackUpdater? = when (val status = sources.status(instance.packRef.origin)) {
        is SourceStatus.Loaded -> status.work.updater ?: throw SourceCannotException(instance.packRef.origin, SourceOperation.Update)
        is SourceStatus.Missing -> throw SourceMissingException(status.origin)
        SourceStatus.None -> null
    }

    /** Whether [instance] has a source to ask, whether or not it can be asked now. */
    private fun hasSource(instance: PackInstance): Boolean = sources.status(instance.packRef.origin) != SourceStatus.None

    override fun handles(instance: PackInstance): Boolean = updaterFor(instance) != null

    override fun describesBuildContents(instance: PackInstance): Boolean =
        updaterFor(instance)?.describesBuildContents(instance) ?: false

    override suspend fun checkForUpdate(instance: PackInstance, forceRefresh: Boolean): UpdateCheck =
        updaterFor(instance)?.checkForUpdate(instance, forceRefresh) ?: UpdateCheck.UpToDate

    override suspend fun previewSwitch(instance: PackInstance, targetVersion: String): UpdateCheck =
        requireUpdater(instance)?.previewSwitch(instance, targetVersion) ?: UpdateCheck.UpToDate

    override suspend fun applyUpdate(
        instance: PackInstance,
        targetVersion: String?,
        progress: ((current: Int, total: Int, path: String) -> Unit)?,
    ): UpdateOutcome =
        requireUpdater(instance)?.applyUpdate(instance, targetVersion, progress) ?: UpdateOutcome.AlreadyCurrent

    override suspend fun availableBuilds(instance: PackInstance): List<PackBuild> =
        updaterFor(instance)?.availableBuilds(instance).orEmpty()

    override fun availableBuildsStream(instance: PackInstance): Flow<List<PackBuild>> =
        updaterFor(instance)?.availableBuildsStream(instance) ?: flowOf(emptyList())

    override fun listSnapshots(instance: PackInstance): List<PackSnapshot> {
        updaterFor(instance)?.let { return it.listSnapshots(instance) }
        return if (hasSource(instance)) snapshots?.list(instance).orEmpty() else emptyList()
    }

    override suspend fun rollback(instance: PackInstance, snapshotId: String): PackInstance {
        updaterFor(instance)?.let { return it.rollback(instance, snapshotId) }
        if (!hasSource(instance)) return instance
        val generic = snapshots ?: throw refusalOf(instance)
        return generic.rollback(instance, snapshotId)
    }

    /** Why the source of [instance], which has one, cannot be asked to update it. */
    private fun refusalOf(instance: PackInstance): SourceRefusedException = when (val status = sources.status(instance.packRef.origin)) {
        is SourceStatus.Missing -> SourceMissingException(status.origin)
        else -> SourceCannotException(instance.packRef.origin, SourceOperation.Update)
    }
}
