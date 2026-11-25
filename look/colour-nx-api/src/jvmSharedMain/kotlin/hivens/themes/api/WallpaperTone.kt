package hivens.themes.api

/**
 * What a wallpaper tells the launcher: its characteristic [colours], best first, and
 * its overall [avgLuminance] (0..1 average brightness, below about 0.5 reads as a dark
 * image). The two are distinct: a dark image with a bright accent has a bright first
 * colour but a low average.
 */
data class WallpaperTone(val colours: List<Int>, val avgLuminance: Float?) {
    val seedArgb: Int? get() = colours.firstOrNull()

    companion object {
        val NONE = WallpaperTone(emptyList(), null)
    }
}
