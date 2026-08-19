package hivens.launcher.update

import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.core.update.PackBuild
import hivens.core.update.PackSnapshot
import hivens.core.update.PackUpdater
import hivens.core.update.UpdateCheck
import hivens.core.update.UpdateOutcome
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * Sends each instance to the updater for the source it came from.
 *
 * The composition root binds this as the single [PackUpdater], so nothing above
 * it knows there is more than one. That is the point: the alternative is every
 * caller asking `origin == Mirror` and being widened by hand each time a source
 * learns to update, which is how the two guards this replaces came to exist.
 *
 * An instance with no updater is not an error. A locally created pack and one
 * synced from a game server genuinely have no version feed, so they answer
 * up-to-date and offer no builds, and the surfaces that ask [handles] first
 * never draw a control that would do nothing.
 */
class RoutingPackUpdater(
    private val byOrigin: Map<PackOrigin, PackUpdater>,
) : PackUpdater {

    private fun updaterFor(instance: PackInstance): PackUpdater? = byOrigin[instance.packRef.origin]

    override fun handles(instance: PackInstance): Boolean = updaterFor(instance) != null

    override suspend fun checkForUpdate(instance: PackInstance, forceRefresh: Boolean): UpdateCheck =
        updaterFor(instance)?.checkForUpdate(instance, forceRefresh) ?: UpdateCheck.UpToDate

    override suspend fun previewSwitch(instance: PackInstance, targetVersion: String): UpdateCheck =
        updaterFor(instance)?.previewSwitch(instance, targetVersion) ?: UpdateCheck.UpToDate

    override suspend fun applyUpdate(
        instance: PackInstance,
        targetVersion: String?,
        progress: ((current: Int, total: Int, path: String) -> Unit)?,
    ): UpdateOutcome =
        updaterFor(instance)?.applyUpdate(instance, targetVersion, progress) ?: UpdateOutcome.AlreadyCurrent

    override suspend fun availableBuilds(instance: PackInstance): List<PackBuild> =
        updaterFor(instance)?.availableBuilds(instance).orEmpty()

    override fun availableBuildsStream(instance: PackInstance): Flow<List<PackBuild>> =
        updaterFor(instance)?.availableBuildsStream(instance) ?: flowOf(emptyList())

    override fun listSnapshots(instance: PackInstance): List<PackSnapshot> =
        updaterFor(instance)?.listSnapshots(instance).orEmpty()

    override suspend fun rollback(instance: PackInstance, snapshotId: String): PackInstance =
        updaterFor(instance)?.rollback(instance, snapshotId) ?: instance
}
