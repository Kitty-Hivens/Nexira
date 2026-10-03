package hivens.ui.screens.custom

import hivens.ui.icons.IconKey
import hivens.ui.icons.NxIcon

/**
 * The icons a made screen can wear, by the name its record stores.
 *
 * A name and not a codepoint, so the record survives the icon font being
 * regenerated, and a short closed list rather than all hundred glyphs, most of
 * which are arrows and checkboxes that mean something else on a rail. A name this
 * build does not know draws [DEFAULT].
 */
internal object ScreenIcons {
    const val DEFAULT = "dashboard"

    val ALL: List<Pair<String, IconKey>> = listOf(
        "dashboard" to NxIcon.Dashboard,
        "star" to NxIcon.Star,
        "favorite" to NxIcon.Favorite,
        "bolt" to NxIcon.Bolt,
        "whatshot" to NxIcon.Whatshot,
        "music" to NxIcon.MusicNote,
        "queue_music" to NxIcon.QueueMusic,
        "image" to NxIcon.Image,
        "palette" to NxIcon.Palette,
        "insights" to NxIcon.Insights,
        "science" to NxIcon.Science,
        "public" to NxIcon.Public,
        "language" to NxIcon.Language,
        "tv" to NxIcon.Tv,
        "computer" to NxIcon.Computer,
        "memory" to NxIcon.Memory,
        "folder" to NxIcon.Folder,
        "description" to NxIcon.Description,
        "code" to NxIcon.Code,
        "widgets" to NxIcon.Widgets,
        "history" to NxIcon.History,
        "lan" to NxIcon.Lan,
        "rule" to NxIcon.Rule,
        "tag" to NxIcon.Tag,
    )

    private val byName = ALL.toMap()

    fun of(name: String): IconKey = byName[name.trim().lowercase()] ?: NxIcon.Dashboard
}
