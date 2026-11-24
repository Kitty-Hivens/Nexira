package hivens.catalogue.api

/** How a source is drawn beside its name. */
sealed interface SourceIcon {
    /**
     * An icon of the look's library, by its Material Symbols name, `storage` or any other
     * the icon axis is asked by. A library that has no such icon draws nothing.
     */
    data class Named(val name: String) : SourceIcon

    /** The source's own mark as SVG, drawn in the ink of the text beside it. */
    data class Svg(val markup: String) : SourceIcon
}

/** One part of a source's pack list, which a person can leave out and take back. */
data class SourceFilterOption(
    /** Stable, since the choice is kept by it. Named by a card in [CatalogueCard.facets]. */
    val id: String,
    /** A key into the words of the module that brings the source. */
    val label: String,
    val icon: SourceIcon? = null,
)

/**
 * A way a source splits its own pack list, shown while Browse lists that source's
 * packs: one switch per option, every one of them on until the person switches it off.
 * A card says which option it falls under in [CatalogueCard.facets] by this filter's
 * [id], and a card that names none of them is always listed. Switching an option off
 * asks the source nothing: what it already listed is filtered where it is shown.
 */
data class SourceFilter(
    val id: String,
    /** The title of the group, a key into the words of the module that brings the source. */
    val label: String,
    val options: List<SourceFilterOption>,
)

/**
 * What the launcher can do with a source's packs beyond listing them. Decided by the work
 * the launcher holds for the source, never declared: only a module compiled into the
 * launcher brings work, on what its source opens, and a jar's source only lists. The pack
 * screens offer a pack only what its source can do, and the launcher refuses anything else
 * asked of it, saying the source cannot do it, or that it is not loaded.
 */
enum class SourceAbility {
    /** Installs one of its builds as a new pack. */
    Install,

    /** Moves a pack it installed to another of its builds, and back to a snapshot. */
    Update,

    /** Puts a pack detached from it back on one of its builds. */
    Reattach,

    /** Checks a pack it installed against the build it is on and puts right what does not match. */
    Repair,
}

/**
 * A pack source as the catalogue lists it to a widget: what to call it and how to draw
 * it, the filters it offers, how its packs are searched, and what it can do with them.
 */
data class SourceView(
    /** The source's id, the one every pack record from it carries. */
    val id: String,
    /** The module that brings it: the namespace [label] and its filters' labels are looked up in. */
    val module: String,
    /** The source's name, a key into [module]'s words. */
    val label: String,
    val icon: SourceIcon? = null,
    val filters: List<SourceFilter> = emptyList(),
    /**
     * The catalogue project type its packs are searched as, where the source lists them
     * through a project search and that search's filters apply. Null where it lists
     * them itself.
     */
    val packType: String? = null,
    /** What the launcher can do with the source's packs beyond listing them, from the work it holds for the source. */
    val abilities: Set<SourceAbility> = emptySet(),
)

/** One option of one filter of one source: what a switch in Browse turns. */
data class SourceOption(val source: String, val filter: String, val option: String)

/** Lists the packs under [option], or leaves them out. */
data class SourceOptionSet(val option: SourceOption, val listed: Boolean)
