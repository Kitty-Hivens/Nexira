package hivens.packs

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
import hivens.launcher.instance.PackInstanceService.DeleteOutcome
import hivens.launcher.launch.LauncherController
import hivens.launcher.update.ReattachConfirmation
import hivens.launcher.update.ReattachPreviewStaleException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("PackActions")

/**
 * What the module does to packs, as the pack commands ask for it.
 *
 * One seam over the launcher's controller and its install, operation and instance
 * services, so a command reads the same whatever is behind it and a test can stand
 * a fake in for all of them.
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
    fun install(pack: CataloguePack, version: CataloguePackVersion)

    /** Forgets a finished install. */
    fun dismissInstall(key: String)

    /** Stops a running install and removes what it had written. */
    fun cancelInstall(key: String)

    /** Moves [pack] to its latest build, or does nothing when other work on it is under way. */
    fun update(pack: PackInstance)

    /** Deletes [pack] and its files, or leaves it when its game is running or other work on it is under way, and says which. */
    suspend fun delete(pack: PackInstance): DeleteOutcome

    /**
     * Puts the detached [pack] back on its source's build as the preview [confirmed]
     * described, or does nothing when it was never detached or other work on it is
     * under way. A pack that no longer measures as the preview said is refused and
     * left as it was, and the refusal is the operation's outcome.
     */
    fun reattach(pack: PackInstance, confirmed: ReattachConfirmation)
}

/** [PackActions] over the launcher's own services. [host] hears of each launch that starts. */
class LauncherPackActions(
    private val controller: LauncherController,
    private val host: PacksHost,
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
        if (started) host.launched(pack)
        return started
    }

    override fun stop(packId: String) = controller.abort(packId)

    override fun install(pack: CataloguePack, version: CataloguePackVersion) {
        val _ = installService.start(pack, version)
    }

    override fun dismissInstall(key: String) = installService.dismiss(key)

    override fun cancelInstall(key: String) = installService.cancel(key)

    override fun update(pack: PackInstance) {
        val _ = operations.startLatestUpdate(pack, updater, hub)
    }

    override suspend fun delete(pack: PackInstance): DeleteOutcome {
        val outcome = instances.deleteCompletely(pack)
        if (outcome != DeleteOutcome.Deleted) log.info("Pack {} was not deleted: {}", pack.id, outcome)
        return outcome
    }

    override fun reattach(pack: PackInstance, confirmed: ReattachConfirmation) {
        if (!instances.canReattach(pack)) {
            log.info("Pack {} was not detached from a source that can take it back", pack.id)
            return
        }
        val _ = operations.startReattach(pack, instances, confirmed)
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
 * Starts putting the detached [pack] back on its source as an operation of this
 * service, so its progress and outcome read like an update's wherever the pack is
 * shown, and closing whatever started it does not cancel it. [confirmed] is the
 * preview the player agreed to. False when the service refused, other work on the
 * pack being under way.
 */
fun PackOperationService.startReattach(pack: PackInstance, instances: PackInstanceService, confirmed: ReattachConfirmation): Boolean =
    start(pack, PackOperationKind.Reattach) { progress ->
        try {
            val back = instances.reattach(pack, confirmed) { c, t, p -> progress(c, t, p.substringAfterLast('/')) }
            PackOperationPhase.Updated(back.pinnedPackVersion ?: back.packRef.version ?: confirmed.build)
        } catch (_: ReattachPreviewStaleException) {
            PackOperationPhase.PreviewStale
        }
    }

/**
 * Starts the game of the pack [packId] as [identity] says, or as the offline name the
 * person chose when nobody is signed in, the way Play on a pack always did. The
 * offline name is read at the press rather than taken from whatever was drawn, since
 * what the game runs as cannot lag behind the settings. With no identity at all
 * nothing starts, which the control offering Play already says. A pack that is not
 * installed fails the way any launch does: logged and told to the host.
 *
 * Pressed while the startup sign-in is still finding out who the launcher is, the
 * launch is refused, as the launcher's own Play control refuses it while it reads
 * "signing in". Started at once it ran under the offline name in front of a stored
 * account a moment from signing in. A launch is not kept waiting either: nothing
 * would show that one is pending, and a sign-in that never settles would hold it
 * for good.
 *
 * A launch that starts takes back the notice that this pack was not installed.
 */
internal fun launchPack(
    packId: String,
    identity: LaunchIdentity,
    packs: IPackRepository,
    actions: PackActions,
    settings: ISettingsService,
    offline: AuthProvider,
    host: PacksHost,
    scope: CoroutineScope,
) {
    val pack = packs.observe().value.firstOrNull { it.id == packId }
    if (pack == null) {
        log.error("Asked to launch pack {}, which is not installed", packId)
        host.packMissing(packId)
        return
    }
    when (identity) {
        is LaunchIdentity.SignedIn -> {
            if (actions.launch(identity.session, pack)) host.packFound(packId)
        }
        LaunchIdentity.Resolving -> log.info("Not launching pack {}: the launcher is still signing in", packId)
        LaunchIdentity.Nobody -> scope.launch {
            val name = withContext(Dispatchers.IO) { settings.getSettings().offlinePlayerName }
            if (name.isNullOrBlank()) return@launch
            val session = withContext(Dispatchers.IO) { offline.login(name, "", "") }
            if (actions.launch(session, pack)) host.packFound(packId)
        }
    }
}

/**
 * Deletes the pack [packId] on [scope], the way the pack's own delete does, and
 * tells [outcomes] how it went. A pack that is not installed is left alone.
 */
internal fun deletePack(packId: String, packs: IPackRepository, actions: PackActions, outcomes: PackOutcomes, scope: CoroutineScope) {
    val pack = packs.observe().value.firstOrNull { it.id == packId } ?: return
    outcomes.deleting(packId)
    scope.launch {
        val outcome = try {
            actions.delete(pack)
        } catch (e: CancellationException) {
            outcomes.deleteFailed(packId, e)
            throw e
        } catch (e: Exception) {
            outcomes.deleteFailed(packId, e)
            throw e
        }
        outcomes.deleted(packId, outcome)
    }
}

/** Updates the pack [packId] to its latest build. A pack that is not installed is left alone. */
internal fun updatePack(packId: String, packs: IPackRepository, actions: PackActions) {
    val pack = packs.observe().value.firstOrNull { it.id == packId } ?: return
    actions.update(pack)
}

/** Puts the detached pack [packId] back on its source as [confirmed] said. A pack that is not installed is left alone. */
internal fun reattachPack(packId: String, confirmed: ReattachConfirmation, packs: IPackRepository, actions: PackActions) {
    val pack = packs.observe().value.firstOrNull { it.id == packId } ?: return
    actions.reattach(pack, confirmed)
}

/** Measures the pack [packId] against its source for a reattach. A pack that is not installed is left alone. */
internal fun previewReattach(packId: String, packs: IPackRepository, previews: ReattachPreviews) {
    val pack = packs.observe().value.firstOrNull { it.id == packId } ?: return
    previews.request(pack)
}
