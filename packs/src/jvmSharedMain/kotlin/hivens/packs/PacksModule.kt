// The packs module is compiled into the application and trusted with the host's own
// services, which it reads through the host view of each level.
@file:OptIn(HostApi::class)

package hivens.packs

import hivens.accounts.LaunchIdentity
import hivens.accounts.LaunchSessions
import hivens.auth.OfflineAuthProvider
import hivens.catalogue.api.PackDetails
import hivens.catalogue.api.PackSources
import hivens.catalogue.api.ProjectBuild
import hivens.core.api.catalogue.CataloguePack
import hivens.core.api.catalogue.CataloguePackVersion
import hivens.core.api.interfaces.IPackRepository
import hivens.core.api.interfaces.ISettingsService
import hivens.core.data.PackOrigin
import hivens.core.update.PackUpdateStatusHub
import hivens.core.update.PackUpdater
import hivens.core.update.VersionChannel
import hivens.launcher.PackInstallService
import hivens.launcher.PackOperationService
import hivens.launcher.instance.PackInstanceService
import hivens.launcher.launch.LauncherController
import hivens.packs.api.InstallRequest
import hivens.packs.api.LaunchView
import hivens.packs.api.PackKeys
import hivens.packs.api.PacksApi
import hivens.widget.api.CompiledModule
import hivens.widget.api.HostApi
import hivens.widget.api.KeyContribution
import hivens.widget.api.ModuleContribution
import hivens.widget.api.ModuleKeys
import hivens.widget.api.ModuleStrings
import hivens.widget.api.command
import hivens.widget.api.find
import hivens.widget.api.flowSource
import hivens.widget.api.get
import hivens.widget.api.hostServices
import hivens.widget.api.service
import hivens.widget.model.ApiVersion
import hivens.widget.model.ScopeLevel
import hivens.widget.model.WidgetDataSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("PacksModule")

/**
 * The packs and launch module as the application carries it: compiled in, and
 * brought in by a document that names [PacksApi.ID] like any jar would be.
 */
object PacksModule : CompiledModule {
    override val id: String = PacksApi.ID
    override val name: String = NAME
    override val strings: ModuleStrings = ModuleStrings.resources(PacksModule::class.java, PacksApi.ID)
    override val provides: Map<String, ApiVersion> = mapOf(PacksApi.ID to PacksApi.VERSION)

    // The API's own object lives in the API artifact's package, so the package is read off it.
    override val apiPackages: Map<String, Set<String>> = mapOf(PacksApi.ID to setOf(PacksApi::class.java.name.substringBeforeLast('.')))
    override val contributions: List<ModuleContribution> = listOf(PackKeysContribution)

    private const val NAME = "packs.name"
}

/**
 * Every key in [PackKeys], answered over the launcher's engine, and the services
 * behind them: the pack actions, the reattach previews and the operation outcomes,
 * one each for the app.
 *
 * The engine's own services and [PacksHost] are the host's, read through the level's
 * host services, which the host opens to the modules compiled into it. A source maps
 * the engine's records into the API's plain data as they change, on the app level's
 * coroutines.
 */
object PackKeysContribution : KeyContribution {
    override val id: String = "packs.keys"
    override val label: String = "Packs"

    override fun declare(keys: ModuleKeys) {
        keys.service(ScopeLevel.App) { context ->
            val services = context.hostServices
            LauncherPackActions(
                controller     = services.get<LauncherController>(),
                host           = services.get<PacksHost>(),
                installService = services.get<PackInstallService>(),
                operations     = services.get<PackOperationService>(),
                updater        = services.get<PackUpdater>(),
                hub            = services.get<PackUpdateStatusHub>(),
                instances      = services.get<PackInstanceService>(),
            ) as PackActions
        }
        keys.service(ScopeLevel.App) { context ->
            val instances = context.hostServices.get<PackInstanceService>()
            val packs = context.hostServices.get<IPackRepository>()
            ReattachPreviews(
                // The record as it is when measured, not as it was asked about: a
                // preview measured again after work on the pack reads what that work left.
                measure    = { pack -> instances.previewReattach(packs.get(pack.id) ?: pack) },
                operations = context.hostServices.get<PackOperationService>().operations,
                scope      = context.coroutineScope,
                discard    = instances::discardReattachPreview,
            )
        }
        keys.service(ScopeLevel.App) { context ->
            PackOutcomes(context.hostServices.get<PackOperationService>().operations, context.coroutineScope)
        }

        keys.source(PackKeys.Library, sample = { emptyList() }) { app ->
            mapped(app.hostServices.get<IPackRepository>().observe(), app.coroutineScope) { all -> all.map { it.toPackView() } }
        }
        keys.source(PackKeys.Launches, sample = { emptyMap() }) { app ->
            flowSource(app.hostServices.get<PacksHost>().launches)
        }
        keys.source(PackKeys.Launch, sample = { LaunchView.Idle }) { app ->
            mapped(app.hostServices.get<PackActions>().launchState, app.coroutineScope) { it.toView() }
        }
        keys.source(PackKeys.Installs, sample = { emptyMap() }) { app ->
            mapped(app.hostServices.get<PackActions>().installs, app.coroutineScope) { all -> all.mapValues { (_, it) -> it.toView() } }
        }
        keys.source(PackKeys.Updates, sample = { emptyMap() }) { app ->
            mapped(app.hostServices.get<PackUpdateStatusHub>().statuses, app.coroutineScope) { all -> all.mapValues { (_, it) -> it.toView() } }
        }
        keys.source(PackKeys.Operations, sample = { emptyMap() }) { app ->
            flowSource(app.hostServices.get<PackOutcomes>().all)
        }
        keys.source(PackKeys.ReattachPreviews, sample = { emptyMap() }) { app ->
            mapped(app.hostServices.get<ReattachPreviews>().previews, app.coroutineScope) { all -> all.mapValues { (_, it) -> it.toView() } }
        }

        // What is done to packs is the same whoever asks. A launch runs as the session
        // the accounts module keeps for the modules the host trusts, read at the press,
        // and as nobody where no accounts module is brought in.
        keys.command(PackKeys.LaunchPack) { app ->
            val services = app.hostServices
            val host = services.get<PacksHost>()
            val packs = services.get<IPackRepository>()
            val actions = services.get<PackActions>()
            val settings = services.get<ISettingsService>()
            val offline = services.get<OfflineAuthProvider>()
            command { id ->
                val identity = services.find<LaunchSessions>()?.identity ?: LaunchIdentity.Nobody
                launchPack(id, identity, packs, actions, settings, offline, host, app.coroutineScope)
            }
        }
        keys.command(PackKeys.StopPack) { app -> command(app.hostServices.get<PackActions>()::stop) }
        keys.command(PackKeys.InstallPack) { app ->
            val actions = app.hostServices.get<PackActions>()
            command { request -> install(request, app.hostServices.find<PackSources>(), actions, app.coroutineScope) }
        }
        keys.command(PackKeys.DismissInstall) { app -> command(app.hostServices.get<PackActions>()::dismissInstall) }
        keys.command(PackKeys.CancelInstall) { app -> command(app.hostServices.get<PackActions>()::cancelInstall) }
        keys.command(PackKeys.UpdatePack) { app ->
            val packs = app.hostServices.get<IPackRepository>()
            val actions = app.hostServices.get<PackActions>()
            command { id -> updatePack(id, packs, actions) }
        }
        keys.command(PackKeys.DeletePack) { app ->
            val packs = app.hostServices.get<IPackRepository>()
            val actions = app.hostServices.get<PackActions>()
            val outcomes = app.hostServices.get<PackOutcomes>()
            command { id -> deletePack(id, packs, actions, outcomes, app.coroutineScope) }
        }
        keys.command(PackKeys.PreviewReattach) { app ->
            val packs = app.hostServices.get<IPackRepository>()
            val previews = app.hostServices.get<ReattachPreviews>()
            command { id -> previewReattach(id, packs, previews) }
        }
        keys.command(PackKeys.ForgetReattachPreview) { app -> command(app.hostServices.get<ReattachPreviews>()::forget) }
        keys.command(PackKeys.ReattachPack) { app ->
            val packs = app.hostServices.get<IPackRepository>()
            val actions = app.hostServices.get<PackActions>()
            command { request -> reattachPack(request.packId, request.confirmation.toEngine(), packs, actions) }
        }
    }
}

/**
 * [flow] read through [transform], as a source that holds its first value at once
 * and follows on [scope] for as long as the level lives.
 */
private fun <A, B> mapped(flow: StateFlow<A>, scope: CoroutineScope, transform: (A) -> B): WidgetDataSource<B> =
    flowSource(flow.map(transform).stateIn(scope, SharingStarted.Eagerly, transform(flow.value)))

/**
 * Looks [request] up in its source and installs that build. The source is the one the
 * catalogue lists, by its id exactly as the request names it, opened where the catalogue
 * opened it, so a source the catalogue refused is not installed from either. It answers
 * over the network, so this runs on [scope]. No catalogue, a source it does not list, a
 * pack the source does not list, or a build the pack does not have installs nothing and
 * is logged.
 */
private fun install(request: InstallRequest, sources: PackSources?, actions: PackActions, scope: CoroutineScope) {
    val catalogue = sources?.listing(request.origin)
    if (catalogue == null) {
        log.warn("Not installing {}: the catalogue lists no source '{}'", request.packId, request.origin)
        return
    }
    scope.launch {
        try {
            val details = catalogue.details(request.packId)
            val version = details.versions.firstOrNull { it.id == request.versionId }
                ?: catalogue.versions(request.packId).firstOrNull { it.id == request.versionId }
            if (version == null) {
                log.warn("Not installing {}: its source lists no build {}", request.packId, request.versionId)
                return@launch
            }
            actions.install(details.toCataloguePack(), version.toCataloguePackVersion())
        } catch (e: Throwable) {
            // A source's own code may fail to link as well as throw. Neither takes the command down.
            if (e is CancellationException || (e !is Exception && e !is LinkageError)) throw e
            log.warn("Not installing {}: its source '{}' could not be asked", request.packId, request.origin, e)
        }
    }
}

/** The pack a page describes, as the card the engine's installer takes. */
internal fun PackDetails.toCataloguePack(): CataloguePack =
    CataloguePack(origin = PackOrigin(origin), id = id, title = title, tagline = tagline, iconUrl = iconUrl, bannerUrl = bannerUrl)

/** A build as the engine's installer takes it. */
internal fun ProjectBuild.toCataloguePackVersion(): CataloguePackVersion = CataloguePackVersion(
    id = id,
    name = name,
    versionNumber = versionNumber,
    mcVersions = gameVersions,
    loaders = loaders,
    downloadUrl = download?.url,
    downloadSha1 = download?.sha1,
    downloadSize = download?.size ?: -1L,
    channel = VersionChannel.of(versionType, versionNumber),
    publishedAt = datePublished.ifBlank { null },
    changelog = changelog,
    downloads = downloads,
    modsCount = modsCount,
    sizeBytes = sizeBytes,
)
