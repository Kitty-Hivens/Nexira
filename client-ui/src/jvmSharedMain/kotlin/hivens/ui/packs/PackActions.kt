package hivens.ui.packs

import hivens.auth.AuthProvider
import hivens.core.api.catalogue.CataloguePack
import hivens.core.api.catalogue.CataloguePackVersion
import hivens.core.api.interfaces.IPackRepository
import hivens.core.api.interfaces.ISettingsService
import hivens.core.data.PackInstance
import hivens.core.data.SessionData
import hivens.core.launch.LaunchState
import hivens.core.update.PackUpdateStatus
import hivens.core.update.PackUpdateStatusHub
import hivens.core.update.PackUpdater
import hivens.core.update.UpdateOutcome
import hivens.launcher.InstallSnapshot
import hivens.launcher.PackInstallService
import hivens.launcher.PackOperationKind
import hivens.launcher.PackOperationPhase
import hivens.launcher.PackOperationService
import hivens.launcher.instance.PackInstanceService
import hivens.launcher.launch.LauncherController
import hivens.ui.notifications.CoreProblem
import hivens.ui.notifications.CoreProblems
import hivens.ui.notifications.LaunchTarget
import hivens.ui.notifications.drivers.LaunchDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("PackActions")

/** A catalogue pack and the build of it to install. */
data class PackInstallRequest(val pack: CataloguePack, val version: CataloguePackVersion)

/**
 * What the launcher does to packs, as the pack commands ask for it.
 *
 * One seam over the launcher's controller and its install, operation and instance
 * services, so a command reads the same whatever is behind it and a test can stand
 * a fake in for all of them. Each action is what the screen offering it did before
 * it was a command.
 */
interface PackActions {
    /** What the one launch the launcher runs at a time is doing. */
    val launchState: StateFlow<LaunchState>

    /** Every install there is, running or finished and not yet dismissed, by its key. */
    val installs: StateFlow<Map<String, InstallSnapshot>>

    /** Starts [pack]'s game as [session]. False when the launcher refused, another launch being under way. */
    fun launch(session: SessionData, pack: PackInstance): Boolean

    /** Asks the game of the pack [packId] to end. */
    fun stop(packId: String)

    /** Installs a catalogue pack, or does nothing when the same build is already being installed. */
    fun install(request: PackInstallRequest)

    /** Forgets a finished install. */
    fun dismissInstall(key: String)

    /** Stops a running install and removes what it had written. */
    fun cancelInstall(key: String)

    /** Moves [pack] to its latest build, or does nothing when other work on it is under way. */
    fun update(pack: PackInstance)

    /** Deletes [pack] and its files, or does nothing when its game is running or other work on it is under way. */
    suspend fun delete(pack: PackInstance)
}

/** [PackActions] over the launcher's own services. */
class LauncherPackActions(
    private val controller: LauncherController,
    private val driver: LaunchDriver,
    private val installService: PackInstallService,
    private val operations: PackOperationService,
    private val updater: PackUpdater,
    private val hub: PackUpdateStatusHub,
    private val instances: PackInstanceService,
) : PackActions {

    override val launchState: StateFlow<LaunchState> get() = controller.state

    override val installs: StateFlow<Map<String, InstallSnapshot>> get() = installService.installs

    override fun launch(session: SessionData, pack: PackInstance): Boolean {
        val started = controller.launchPackInstance(session, pack)
        if (started) driver.observe(LaunchTarget(pack))
        return started
    }

    override fun stop(packId: String) = controller.abort(packId)

    override fun install(request: PackInstallRequest) {
        val _ = installService.start(request.pack, request.version)
    }

    override fun dismissInstall(key: String) = installService.dismiss(key)

    override fun cancelInstall(key: String) = installService.cancel(key)

    override fun update(pack: PackInstance) {
        val _ = operations.startLatestUpdate(pack, updater, hub)
    }

    override suspend fun delete(pack: PackInstance) {
        val outcome = instances.deleteCompletely(pack)
        if (outcome != PackInstanceService.DeleteOutcome.Deleted) log.info("Pack {} was not deleted: {}", pack.id, outcome)
    }
}

/**
 * Starts moving [pack] to its latest build as an operation of this service, which
 * owns it for as long as it takes, so closing whatever started it does not cancel
 * it. False when the service refused, other work on the pack being under way.
 *
 * The badges that show a waiting update are told from inside the operation rather
 * than by whoever started it, so they agree with the files whether or not that
 * surface is still open.
 */
fun PackOperationService.startLatestUpdate(pack: PackInstance, updater: PackUpdater, hub: PackUpdateStatusHub): Boolean {
    val current = pack.pinnedPackVersion ?: pack.packRef.version ?: "?"
    return start(pack, PackOperationKind.Update) { progress ->
        val outcome = updater.applyUpdate(pack, null) { c, t, p ->
            progress(c, t, p.substringAfterLast('/'))
        }
        hub.report(pack.id, PackUpdateStatus.UpToDate)
        PackOperationPhase.Updated((outcome as? UpdateOutcome.Applied)?.toVersion ?: current)
    }
}

/**
 * Starts the game of the pack [packId] as [session], or as the offline name the
 * person chose when nobody is signed in, the way Play on a pack always did. The
 * offline name is read at the press rather than taken from whatever was drawn,
 * since what the game runs as cannot lag behind the settings. With no identity at
 * all nothing starts, which the control offering Play already says. A pack that is
 * not installed fails the way any launch does: logged and told to [problems].
 */
internal fun launchPack(
    packId: String,
    session: SessionData?,
    packs: IPackRepository,
    actions: PackActions,
    settings: ISettingsService,
    offline: AuthProvider,
    problems: CoreProblems,
    scope: CoroutineScope,
) {
    val pack = packs.observe().value.firstOrNull { it.id == packId }
    if (pack == null) {
        log.error("Asked to launch pack {}, which is not installed", packId)
        problems.report(CoreProblem.PackNotInstalled(packId))
        return
    }
    if (session != null) {
        val _ = actions.launch(session, pack)
        return
    }
    scope.launch {
        val name = withContext(Dispatchers.IO) { settings.getSettings().offlinePlayerName }
        if (name.isNullOrBlank()) return@launch
        val identity = withContext(Dispatchers.IO) { offline.login(name, "", "") }
        val _ = actions.launch(identity, pack)
    }
}

/** Deletes the pack [packId] on [scope], the way the pack's own delete does. A pack that is not installed is left alone. */
internal fun deletePack(packId: String, packs: IPackRepository, actions: PackActions, scope: CoroutineScope) {
    val pack = packs.observe().value.firstOrNull { it.id == packId } ?: return
    scope.launch { actions.delete(pack) }
}

/** Updates the pack [packId] to its latest build. A pack that is not installed is left alone. */
internal fun updatePack(packId: String, packs: IPackRepository, actions: PackActions) {
    val pack = packs.observe().value.firstOrNull { it.id == packId } ?: return
    actions.update(pack)
}
