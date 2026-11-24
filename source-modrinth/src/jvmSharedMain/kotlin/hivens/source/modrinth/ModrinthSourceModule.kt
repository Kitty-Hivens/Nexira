// The Modrinth source is compiled into the application and trusted with the host's own
// services: it reads Modrinth through the engine's client and installs and updates through
// the engine's archive installer and updater, which only the host holds.
@file:OptIn(HostApi::class)

package hivens.source.modrinth

import hivens.catalogue.api.CatalogueCard
import hivens.catalogue.api.PackListing
import hivens.catalogue.api.PackSourceContribution
import hivens.catalogue.api.ProjectSearchContribution
import hivens.catalogue.api.ProjectSearcher
import hivens.catalogue.api.ProjectTypes
import hivens.catalogue.api.SourceIcon
import hivens.core.api.interfaces.IPackRepository
import hivens.core.cache.CacheConfig
import hivens.launcher.cache.CacheFactory
import hivens.launcher.modrinth.ModrinthClient
import hivens.launcher.mrpack.MrpackInstaller
import hivens.launcher.smrt.ModStraySweep
import hivens.launcher.sources.EngineSources
import hivens.launcher.sources.SourcePackWork
import hivens.launcher.update.ApplyJournal
import hivens.launcher.update.ArchivePackUpdater
import hivens.launcher.update.PackSnapshotService
import hivens.widget.api.CompiledModule
import hivens.widget.api.HostApi
import hivens.widget.api.KeyContribution
import hivens.widget.api.LevelServices
import hivens.widget.api.ModuleContribution
import hivens.widget.api.ModuleKeys
import hivens.widget.api.ModuleStrings
import hivens.widget.api.get
import hivens.widget.api.service
import hivens.widget.model.ScopeLevel
import java.nio.file.Path

/**
 * Modrinth as a source of the catalogue, as the application carries it: compiled in,
 * and brought in by a document that names [ID] like any jar would be. It brings two
 * sources, its modpacks and its project search, the second also listing the first.
 */
object ModrinthSourceModule : CompiledModule {
    const val ID: String = "nexira.source.modrinth"

    override val id: String = ID
    override val name: String = NAME
    override val strings: ModuleStrings = ModuleStrings.resources(ModrinthSourceModule::class.java, ID)
    override val contributions: List<ModuleContribution> = listOf(ModrinthSourceKeys, ModrinthPackSource, ModrinthProjectSource)

    private const val NAME = "modrinth.name"
}

/**
 * The pack listing, through a search cache for the app, which is also what Modrinth does
 * to the packs it installs, and the project search, all read through the engine's client.
 */
private class ModrinthAnswers(val packs: ModrinthPacks, val projects: ProjectSearcher)

/** Modrinth's pack listing, answering as well for what Modrinth does to its packs. */
private class ModrinthPacks(listing: PackListing, work: SourcePackWork) : PackListing by listing, SourcePackWork by work

/**
 * Makes what answers for Modrinth, once for the app, where the module is trusted with
 * the engine's Modrinth client, its archive installer and the update machinery. The two
 * sources hand the catalogue that, and the pack source hands the engine its work.
 */
object ModrinthSourceKeys : KeyContribution {
    override val id: String = "modrinth.source"
    override val label: String = "Modrinth"

    override fun declare(keys: ModuleKeys) {
        keys.service(ScopeLevel.App) { context ->
            val client = context.hostServices.get<ModrinthClient>()
            // Single-flight with five minutes stale-while-revalidate, keyed by source,
            // page and query. In memory on purpose: a listing written to disk would be
            // the first thing shown on the next launch, and a catalogue has moved on by
            // then. An empty result is not stored, so a source briefly unreachable does
            // not leave an empty Browse behind it.
            val searches = context.hostServices.get<CacheFactory>().createInMemory<List<CatalogueCard>>(
                "catalogue-search",
                CacheConfig(
                    ttlMs = 5 * 60_000L,
                    staleTtlMs = Long.MAX_VALUE,
                    maxEntries = 64,
                    shouldStore = { it.isNotEmpty() },
                ),
            )
            val host = context.hostServices
            val mrpack = host.get<MrpackInstaller>()
            val archives = ArchivePackUpdater(
                feed = ModrinthArchiveFeed(client),
                installer = mrpack,
                repository = host.get<IPackRepository>(),
                snapshotService = host.get<PackSnapshotService>(),
                journal = host.get<ApplyJournal>(),
                dataDir = host.get<Path>(),
                sweep = host.get<ModStraySweep>(),
            )
            ModrinthAnswers(
                ModrinthPacks(CachedPackListing(ModrinthListing(client), ModrinthPackSource.ID, searches), ModrinthPackWork(client, mrpack, archives)),
                ModrinthProjects(client),
            )
        }
    }
}

/**
 * Modrinth's modpacks as a pack source. Browse lists them through Modrinth's project
 * search, as the modpack project type, so its filters, sorts and tags apply to them.
 * It installs, updates and reattaches its packs. It does not repair them, since it
 * publishes no per-file list of a build to measure a pack against.
 */
object ModrinthPackSource : PackSourceContribution {
    /** The id every Modrinth pack record carries. */
    val ID: String = EngineSources.Modrinth.id

    const val LABEL: String = "source.name"

    override val id: String = ID
    override val label: String = LABEL
    override val icon: SourceIcon = SourceIcon.Named("public")
    override val packType: String = ProjectTypes.MODPACK

    override fun open(services: LevelServices): PackListing = services.get<ModrinthAnswers>().packs
}

/** Modrinth's project search: mods, resource packs, shaders and modpacks. */
object ModrinthProjectSource : ProjectSearchContribution {
    override val id: String = ModrinthPackSource.ID
    override val label: String = ModrinthPackSource.LABEL
    override val types: Set<String> = setOf(ProjectTypes.MOD, ProjectTypes.RESOURCE_PACK, ProjectTypes.SHADER, ProjectTypes.MODPACK)

    override fun open(services: LevelServices): ProjectSearcher = services.get<ModrinthAnswers>().projects
}
