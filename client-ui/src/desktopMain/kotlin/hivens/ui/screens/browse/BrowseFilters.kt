package hivens.ui.screens.browse

import hivens.core.api.dto.modrinth.ModrinthDisclosure
import hivens.core.api.dto.modrinth.ModrinthGameVersion
import hivens.core.data.PackOrigin
import hivens.launcher.instance.ContentKind
import hivens.launcher.modrinth.ENV_CLIENT
import hivens.launcher.modrinth.ENV_SERVER
import hivens.launcher.modrinth.FilterField
import hivens.launcher.modrinth.ModrinthClient
import hivens.launcher.modrinth.SearchFilter
import hivens.ui.i18n.AppStrings
import hivens.ui.icons.IconKey
import hivens.ui.icons.NxIcon
import hivens.ui.screens.mod.groupGameVersions
import hivens.ui.screens.mod.loaderLabel

/**
 * The catalogue project type a search is for: the kind's own, or modpacks while
 * packs are listed from the catalogue. Null where the source is not the catalogue,
 * which has no filters to offer.
 */
internal fun catalogueTypeOf(kind: ContentKind?, origin: PackOrigin): String? = when {
    kind != null -> ModrinthClient.projectTypeOf(kind)
    origin == PackOrigin.Modrinth -> MODPACK
    else -> null
}

internal const val MODPACK = "modpack"

/**
 * One block of the filter rail, after the catalogue's own sidebar: what it narrows,
 * which project types have it, and how its choices are read.
 *
 * Categories are four groups and not one, because the catalogue files them under
 * four headers and a resource pack's resolution is a different question from its
 * style.
 */
internal enum class FilterGroup(
    val field: FilterField,
    /** The catalogue's header for a category group, null for the rest. */
    val header: String? = null,
    /** Whether a choice can also be ruled out. */
    val excludes: Boolean = true,
) {
    GameVersion(FilterField.GameVersion, excludes = false),
    Loader(FilterField.Loader),
    Categories(FilterField.Category, header = "categories"),
    Features(FilterField.Category, header = "features"),
    Resolutions(FilterField.Resolution, header = "resolutions"),
    Performance(FilterField.Category, header = "performance impact"),
    Environment(FilterField.Environment, excludes = false),
    License(FilterField.OpenSource),
    Exclusions(FilterField.Disclosure),
}

internal fun FilterGroup.title(s: AppStrings): String = when (this) {
    FilterGroup.GameVersion -> s.browseFilterVersion
    FilterGroup.Loader -> s.browseFilterLoader
    FilterGroup.Categories -> s.browseFilterCategories
    FilterGroup.Features -> s.browseFilterFeatures
    FilterGroup.Resolutions -> s.browseFilterResolutions
    FilterGroup.Performance -> s.browseFilterPerformance
    FilterGroup.Environment -> s.browseFilterEnvironment
    FilterGroup.License -> s.browseFilterLicense
    FilterGroup.Exclusions -> s.browseFilterExclusions
}

/** How a choice is marked beside its name. */
internal sealed interface ChoiceMark {
    data class Glyph(val icon: IconKey) : ChoiceMark

    /** A loader's own logo, see [hivens.ui.components.LoaderGlyph]. */
    data class Loader(val name: String) : ChoiceMark

    /** The catalogue's own SVG, drawn in the row's ink. */
    data class Svg(val source: String) : ChoiceMark
}

/**
 * One choice in a group. [filter] is it asked for; ruling it out is the same value
 * excluded. [secondary] choices are folded behind "show more", the way the catalogue
 * shows the three loaders most packs run and keeps the rest a click away.
 */
internal class FilterChoice(
    val filter: SearchFilter,
    val label: String,
    val mark: ChoiceMark? = null,
    val secondary: Boolean = false,
)

/**
 * The choices [group] offers for [type], from the catalogue's lists. Empty where the
 * group does not apply to the type or the lists are not read yet, which is what
 * keeps a block off the rail.
 */
internal fun choicesFor(
    group: FilterGroup,
    type: String,
    tags: BrowseTags.Tags?,
    s: AppStrings,
    allVersions: Boolean = false,
): List<FilterChoice> = when (group) {
    FilterGroup.GameVersion -> tags?.gameVersions.orEmpty()
        .filter { allVersions || it.versionType == ModrinthGameVersion.RELEASE }
        .map { FilterChoice(SearchFilter(FilterField.GameVersion, it.version), it.version) }
    FilterGroup.Loader -> {
        val defaults = DEFAULT_LOADERS[type].orEmpty()
        tags?.loaders.orEmpty()
            .filter { loader -> loaderFits(type, loader.supportedProjectTypes) }
            .map { loader ->
                FilterChoice(
                    SearchFilter(FilterField.Loader, loader.name),
                    loaderLabel(loader.name),
                    ChoiceMark.Loader(loader.name),
                    secondary = defaults.isNotEmpty() && loader.name !in defaults,
                )
            }
            .sortedBy { it.secondary }
    }
    FilterGroup.Categories, FilterGroup.Features, FilterGroup.Resolutions, FilterGroup.Performance ->
        tags?.categories.orEmpty()
            .filter { it.projectType == type && it.header == group.header }
            .map { tag ->
                FilterChoice(
                    SearchFilter(group.field, tag.name),
                    s.modrinthCategory(tag.name),
                    tag.icon.takeIf { it.isNotBlank() }?.let { ChoiceMark.Svg(it) },
                )
            }
            .let { choices -> if (group == FilterGroup.Resolutions) choices.sortedBy { resolutionOrder(it.filter.value) } else choices.sortedBy { it.label } }
    FilterGroup.Environment -> if (type == ModrinthClient.projectTypeOf(ContentKind.Mod) || type == MODPACK) {
        listOf(
            FilterChoice(SearchFilter(FilterField.Environment, ENV_CLIENT), s.browseFilterClient, ChoiceMark.Glyph(NxIcon.Computer)),
            FilterChoice(SearchFilter(FilterField.Environment, ENV_SERVER), s.browseFilterServer, ChoiceMark.Glyph(NxIcon.Storage)),
        )
    } else {
        emptyList()
    }
    FilterGroup.License -> listOf(
        FilterChoice(SearchFilter(FilterField.OpenSource, OPEN_SOURCE), s.browseFilterOpenSource, ChoiceMark.Glyph(NxIcon.Code)),
    )
    FilterGroup.Exclusions -> DISCLOSURES
        .filter { (kind, _) -> kind !in MODS_AND_MODPACKS_ONLY || type == ModrinthClient.projectTypeOf(ContentKind.Mod) || type == MODPACK }
        .map { (kind, icon) -> FilterChoice(SearchFilter(FilterField.Disclosure, kind, excluded = true), disclosureLabel(kind, s), ChoiceMark.Glyph(icon)) }
}

/** The value a licence choice carries. The field is a boolean and the value is ignored. */
internal const val OPEN_SOURCE = "open_source"

/**
 * Which loaders a type is filtered by: those it publishes for, without the server
 * platforms and data packs, which share the mod type in the catalogue's list but
 * are not what a mod search means.
 *
 * None for a resource pack. Its only loader is `minecraft`, and the catalogue keeps
 * loaders and resolutions in one index field, so the two chosen together were sent
 * as one either-of: picking Minecraft beside `16x` matched every resource pack and
 * the resolution narrowed nothing.
 */
private fun loaderFits(type: String, supported: List<String>): Boolean = when (type) {
    "mod" -> "mod" in supported && "plugin" !in supported && "datapack" !in supported
    "resourcepack" -> false
    else -> type in supported
}

/** The loaders shown before "show more", the catalogue's own defaults. */
private val DEFAULT_LOADERS = mapOf(
    "mod" to listOf("fabric", "forge", "neoforge"),
    "shader" to listOf("iris", "optifine", "vanilla"),
)

/** Resolutions read smallest first, which is not how they sort as text. */
private fun resolutionOrder(value: String): Int = value.takeWhile { it.isDigit() }.toIntOrNull() ?: 0

/**
 * What can be ruled out by its author's own declaration, in the order a reader
 * scans for it. Telemetry and system interactions are declared for mods and
 * modpacks only, so the other kinds do not offer them.
 */
private val DISCLOSURES = listOf(
    ModrinthDisclosure.TELEMETRY to NxIcon.Wifi,
    ModrinthDisclosure.ADVERTISEMENTS to NxIcon.Campaign,
    ModrinthDisclosure.PAID_FEATURES to NxIcon.Paid,
    ModrinthDisclosure.AI_CONTENT to NxIcon.Science,
    ModrinthDisclosure.AI_FUNCTIONALITY to NxIcon.Science,
    ModrinthDisclosure.SYSTEM_INTERACTIONS to NxIcon.Computer,
    ModrinthDisclosure.EPILEPSY_TRIGGERS to NxIcon.Visibility,
    ARCHIVED to NxIcon.Inventory2,
)

private const val ARCHIVED = "archived"

private val MODS_AND_MODPACKS_ONLY = setOf(ModrinthDisclosure.TELEMETRY, ModrinthDisclosure.SYSTEM_INTERACTIONS)

private fun disclosureLabel(kind: String, s: AppStrings): String = when (kind) {
    ModrinthDisclosure.TELEMETRY -> s.modDisclosureTelemetry
    ModrinthDisclosure.ADVERTISEMENTS -> s.modDisclosureAds
    ModrinthDisclosure.PAID_FEATURES -> s.modDisclosurePaid
    ModrinthDisclosure.AI_CONTENT -> s.modDisclosureAiContent
    ModrinthDisclosure.AI_FUNCTIONALITY -> s.modDisclosureAiFunctionality
    ModrinthDisclosure.SYSTEM_INTERACTIONS -> s.modDisclosureSystem
    ModrinthDisclosure.EPILEPSY_TRIGGERS -> s.modDisclosureEpilepsy
    else -> s.browseFilterArchived
}

/** The name a chosen filter is shown by in a chip, when the group's choices are not at hand. */
internal fun filterLabel(filter: SearchFilter, s: AppStrings): String = when (filter.field) {
    FilterField.GameVersion -> filter.value
    FilterField.Loader -> loaderLabel(filter.value)
    FilterField.Category, FilterField.Resolution -> s.modrinthCategory(filter.value)
    FilterField.Environment -> if (filter.value == ENV_CLIENT) s.browseFilterClient else s.browseFilterServer
    FilterField.OpenSource -> s.browseFilterOpenSource
    FilterField.Disclosure -> disclosureLabel(filter.value, s)
    FilterField.Project -> filter.value
}

/** One chip under the search, standing for one chosen filter or several. */
internal class ChosenChip(val label: String, val filters: List<SearchFilter>, val excluded: Boolean = false)

/**
 * The chosen filters as chips, game versions folded.
 *
 * Every chosen version as its own chip ran to seven for one minor line, which is
 * what a tag like `1.20.x` asks for. Versions fold the way a project's page folds
 * them: a whole major is `1.20.x`, a run inside one is `1.20.2-1.20.4`, and a chip
 * takes all of its versions off at once. A version the fold has no place for, a
 * snapshot beside releases, keeps a chip of its own, so every choice stays
 * removable. Without the catalogue's list nothing is folded.
 */
internal fun chosenChips(chosen: Collection<SearchFilter>, versions: List<ModrinthGameVersion>, s: AppStrings): List<ChosenChip> {
    val sorted = chosen.sortedWith(compareBy({ it.field.ordinal }, { it.value }))
    val (picked, rest) = sorted.partition { it.field == FilterField.GameVersion && !it.excluded }
    val groups = groupGameVersions(picked.map { it.value }, versions)
    val folded = groups.map { g -> ChosenChip(g.label, g.versions.map { SearchFilter(FilterField.GameVersion, it) }) }
    val covered = groups.flatMapTo(HashSet()) { it.versions }
    val loose = picked.filter { it.value !in covered }.map { ChosenChip(it.value, listOf(it)) }
    return folded + loose + rest.map { ChosenChip(filterLabel(it, s), listOf(it), it.excluded) }
}
