// The mirror source is compiled into the application and trusted with the host's own
// services: it reads the mirror through the engine's client, which only the host holds.
@file:OptIn(HostApi::class)

package hivens.source.mirror

import hivens.catalogue.api.PackListing
import hivens.catalogue.api.PackSourceContribution
import hivens.catalogue.api.SourceFilter
import hivens.catalogue.api.SourceFilterOption
import hivens.catalogue.api.SourceIcon
import hivens.core.api.interfaces.ISettingsService
import hivens.core.api.interfaces.ReadingLanguage
import hivens.launcher.smrt.SmrtPackClient
import hivens.launcher.sources.EngineSources
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
    override val strings: ModuleStrings = ModuleStrings(
        mapOf(
            "en" to mapOf(
                NAME to "Hivens mirror",
                MirrorPackSource.LABEL to "Hivens",
                MirrorPackSource.BUILT_BY_LABEL to "Built by",
                MirrorPackSource.COMMUNITY_LABEL to "Community",
            ),
            "ru" to mapOf(
                NAME to "Зеркало Hivens",
                MirrorPackSource.LABEL to "Hivens",
                MirrorPackSource.BUILT_BY_LABEL to "Кто собрал",
                MirrorPackSource.COMMUNITY_LABEL to "Сообщество",
            ),
            "de" to mapOf(
                NAME to "Hivens-Spiegel",
                MirrorPackSource.LABEL to "Hivens",
                MirrorPackSource.BUILT_BY_LABEL to "Erstellt von",
                MirrorPackSource.COMMUNITY_LABEL to "Community",
            ),
            "ja" to mapOf(
                NAME to "Hivens ミラー",
                MirrorPackSource.LABEL to "Hivens",
                MirrorPackSource.BUILT_BY_LABEL to "作成者",
                MirrorPackSource.COMMUNITY_LABEL to "コミュニティ",
            ),
        ),
    )
    override val contributions: List<ModuleContribution> = listOf(MirrorSourceKeys, MirrorPackSource)

    private const val NAME = "mirror.name"
}

/**
 * Makes what answers for the mirror, once for the app, where the module is trusted with
 * the engine's mirror client. [MirrorPackSource] hands the catalogue that.
 */
object MirrorSourceKeys : KeyContribution {
    override val id: String = "mirror.source"
    override val label: String = "Hivens mirror"

    override fun declare(keys: ModuleKeys) {
        keys.service(ScopeLevel.App) { context ->
            val host = context.hostServices
            val settings = host.get<ISettingsService>()
            val reading = host.find<ReadingLanguage>()
            MirrorListing(host.get<SmrtPackClient>(), languages = { reading?.tags() ?: listOf(settings.getSettings().locale) })
        }
    }
}

/**
 * The mirror's listing as a pack source. It answers with its whole listing whatever page
 * is asked, so it does not page, and it splits its list by who built a pack: the mirror
 * itself or a member of its community.
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

    override fun open(services: LevelServices): PackListing = services.get<MirrorListing>()
}
