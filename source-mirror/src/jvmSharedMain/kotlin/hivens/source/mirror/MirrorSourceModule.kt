// The mirror source is compiled into the application and trusted with the host's own
// services: it reads the mirror through the engine's client and installs and updates
// through the engine's sync, which only the host holds.
@file:OptIn(HostApi::class)

package hivens.source.mirror

import hivens.catalogue.api.PackListing
import hivens.catalogue.api.PackSourceContribution
import hivens.catalogue.api.SourceFilter
import hivens.catalogue.api.SourceFilterOption
import hivens.catalogue.api.SourceIcon
import hivens.core.api.interfaces.ISettingsService
import hivens.core.api.interfaces.ReadingLanguage
import hivens.launcher.PackInstaller
import hivens.launcher.smrt.SmrtPackClient
import hivens.launcher.sources.EngineSources
import hivens.launcher.sources.SourcePackWork
import hivens.launcher.update.PackUpdateService
import hivens.ui.icons.NxIcon
import hivens.widget.api.CompiledModule
import hivens.widget.api.HostApi
import hivens.widget.api.KeyContribution
import hivens.widget.api.LevelServices
import hivens.widget.api.ModuleContribution
import hivens.widget.api.ModuleKeys
import hivens.widget.api.ModuleStrings
import hivens.widget.api.find
import hivens.widget.api.get
import hivens.widget.api.service
import hivens.widget.model.ScopeLevel

/**
 * The Hivens mirror as a source of the catalogue, as the application carries it:
 * compiled in, and brought in by a document that names [ID] like any jar would be.
 */
object MirrorSourceModule : CompiledModule {
    const val ID: String = "nexira.source.mirror"

    override val id: String = ID
    override val name: String = NAME
    override val strings: ModuleStrings = ModuleStrings.resources(MirrorSourceModule::class.java, ID)
    override val contributions: List<ModuleContribution> = listOf(MirrorSourceKeys, MirrorPackSource)

    private const val NAME = "mirror.name"
}

/** The mirror's listing, answering as well for what the mirror does to its packs. */
class MirrorPacks(val listing: MirrorListing, work: SourcePackWork) : PackListing by listing, SourcePackWork by work

/**
 * Makes what answers for the mirror, once for the app, where the module is trusted with
 * the engine's mirror client, its installer and its updater. [MirrorPackSource] hands the
 * catalogue that, and the engine its work.
 */
object MirrorSourceKeys : KeyContribution {
    override val id: String = "mirror.source"
    override val label: String = "Hivens mirror"

    override fun declare(keys: ModuleKeys) {
        keys.service(ScopeLevel.App) { context ->
            val host = context.hostServices
            val settings = host.get<ISettingsService>()
            val reading = host.find<ReadingLanguage>()
            val client = host.get<SmrtPackClient>()
            MirrorPacks(
                MirrorListing(client, languages = { reading?.tags() ?: listOf(settings.getSettings().locale) }),
                MirrorPackWork(client, host.get<PackInstaller>(), host.get<PackUpdateService>()),
            )
        }
    }
}

/**
 * The mirror's listing as a pack source. It answers with its whole listing whatever page
 * is asked, so it does not page, and it splits its list by who built a pack: the mirror
 * itself or a member of its community. It installs, updates, reattaches and repairs its
 * packs.
 */
object MirrorPackSource : PackSourceContribution {
    /** The id every mirror pack record carries. */
    val ID: String = EngineSources.Mirror.id

    /** The filter a card names under [CatalogueCard.facets][hivens.catalogue.api.CatalogueCard.facets], and its two options. */
    const val BUILT_BY: String = "builtBy"
    const val OWN: String = "own"
    const val COMMUNITY: String = "community"

    const val LABEL: String = "source.name"
    const val BUILT_BY_LABEL: String = "filter.builtBy"
    const val COMMUNITY_LABEL: String = "filter.builtBy.community"

    override val id: String = ID
    override val label: String = LABEL
    override val icon: SourceIcon = SourceIcon.Glyph(NxIcon.Storage.codepoint)
    override val paged: Boolean = false
    override val filters: List<SourceFilter> = listOf(
        SourceFilter(
            id = BUILT_BY,
            label = BUILT_BY_LABEL,
            options = listOf(
                // The mirror's own packs go by the mirror's name.
                SourceFilterOption(OWN, LABEL, SourceIcon.Glyph(NxIcon.Verified.codepoint)),
                SourceFilterOption(COMMUNITY, COMMUNITY_LABEL, SourceIcon.Glyph(NxIcon.Groups.codepoint)),
            ),
        ),
    )

    override fun open(services: LevelServices): PackListing = services.get<MirrorPacks>()
}
