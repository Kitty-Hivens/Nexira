package hivens.themes

import hivens.ui.theme.Theme

/**
 * The themes a person can choose from and the one in use: everything that ships, the one
 * made from their wallpaper, the ones modules brought, and the ones that are their own.
 *
 * Their own are themes they brought in through a file or that an older build stored in a
 * shape this one has replaced. A [selected] theme that does not exist lands on the default
 * rather than on nothing, and so does the wallpaper theme while there is no wallpaper to
 * make it from. The selection is kept as it is, so the theme comes back once it exists again.
 *
 * [wallpaper] is the last wallpaper's colours, best first. Kept with the library so a
 * launcher that opens on the wallpaper theme opens in it, rather than in the default until
 * the picture has decoded.
 */
data class ThemeLibrary(
    val selected: String = Themes.default.id,
    val own: List<Theme> = emptyList(),
    val wallpaper: List<Int> = emptyList(),
    /**
     * The themes modules brought, which the manager fills in from whatever is loaded. Never
     * stored: a module that is off or gone takes its themes with it, and a selection that named
     * one lands on the default until the module is back.
     */
    val contributed: List<Theme> = emptyList(),
) {
    /** The theme made from [wallpaper], null without one. Named in English: a screen shows its own word for it. */
    val fromWallpaper: Theme? by lazy { themeFromWallpaper(wallpaper, "Wallpaper") }

    val all: List<Theme> get() = Themes.all + listOfNotNull(fromWallpaper) + contributed + own

    val active: Theme get() = (RENAMED[selected] ?: selected).let { id -> all.firstOrNull { it.id == id } } ?: Themes.default

    private companion object {
        /** Themes that ship under another id now. Lotus Dark became the whole of Lotus. */
        val RENAMED: Map<String, String> = mapOf("lotus-dark" to Themes.Lotus.id)
    }
}
