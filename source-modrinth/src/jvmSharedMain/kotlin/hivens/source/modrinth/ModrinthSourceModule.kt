// The Modrinth source is compiled into the application and trusted with the host's own
// services: it reads Modrinth through the engine's client, which only the host holds.
@file:OptIn(HostApi::class)

package hivens.source.modrinth

import hivens.catalogue.api.CatalogueCard
import hivens.catalogue.api.PackListing
import hivens.catalogue.api.PackSourceContribution
import hivens.catalogue.api.ProjectSearchContribution
import hivens.catalogue.api.ProjectSearcher
import hivens.catalogue.api.ProjectTypes
import hivens.catalogue.api.SourceIcon
import hivens.core.cache.CacheConfig
import hivens.launcher.cache.CacheFactory
import hivens.launcher.modrinth.ModrinthClient
import hivens.launcher.sources.EngineSources
import hivens.ui.icons.NxIcon
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

/**
 * Modrinth as a source of the catalogue, as the application carries it: compiled in,
 * and brought in by a document that names [ID] like any jar would be. It brings two
 * sources, its modpacks and its project search, the second also listing the first.
 */
object ModrinthSourceModule : CompiledModule {
    const val ID: String = "nexira.source.modrinth"

    override val id: String = ID
    override val name: String = NAME
    override val strings: ModuleStrings = ModuleStrings(
        mapOf(
            "en" to mapOf(NAME to "Modrinth", ModrinthPackSource.LABEL to "Modrinth"),
            "ru" to mapOf(NAME to "Modrinth", ModrinthPackSource.LABEL to "Modrinth"),
            "de" to mapOf(NAME to "Modrinth", ModrinthPackSource.LABEL to "Modrinth"),
            "ja" to mapOf(NAME to "Modrinth", ModrinthPackSource.LABEL to "Modrinth"),
        ),
    )
    override val contributions: List<ModuleContribution> = listOf(ModrinthSourceKeys, ModrinthPackSource, ModrinthProjectSource)

    private const val NAME = "modrinth.name"
}

/** The pack listing, through a search cache for the app, and the project search, both read through the engine's client. */
private class ModrinthAnswers(val packs: PackListing, val projects: ProjectSearcher)

/**
 * Makes what answers for Modrinth, once for the app, where the module is trusted with
 * the engine's Modrinth client. The two sources hand the catalogue that.
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
            ModrinthAnswers(CachedPackListing(ModrinthListing(client), ModrinthPackSource.ID, searches), ModrinthProjects(client))
        }
    }
}

/**
 * Modrinth's modpacks as a pack source. Browse lists them through Modrinth's project
 * search, as the modpack project type, so its filters, sorts and tags apply to them.
 */
object ModrinthPackSource : PackSourceContribution {
    /** The id every Modrinth pack record carries. */
    val ID: String = EngineSources.Modrinth.id

    const val LABEL: String = "source.name"

    override val id: String = ID
    override val label: String = LABEL
    override val icon: SourceIcon = SourceIcon.Glyph(NxIcon.Public.codepoint)
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
