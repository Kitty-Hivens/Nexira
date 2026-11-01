package hivens.ui.theme

import androidx.compose.ui.graphics.Color
import com.materialkolor.hct.Hct
import kotlin.math.max

/** The id of the theme made from the wallpaper. It is never stored as a theme, only selected. */
const val WALLPAPER_THEME_ID = "wallpaper"

/**
 * A theme authored from a wallpaper's [colours], best first, as [coloursFromImage]
 * ranks them.
 *
 * It is a theme like any other, not a filter over the active one: the ladder and inks
 * are Celestia's, measured tone for tone, tinted toward the wallpaper's first colour,
 * and the wallpaper's colours are its colours, moved in tone into the band a colour of
 * that mode lives in so the first of them can carry a filled control. Both modes are
 * made, so the day and night switch keeps working.
 *
 * A picture with no colour in it gives a neutral ladder in the hue it has and
 * Celestia's colours. Null when there is nothing to make it from.
 */
fun themeFromWallpaper(colours: List<Int>, name: String): Theme? {
    if (colours.isEmpty()) return null
    val hcts = colours.map { Hct.fromInt(it) }
    val vivid = hcts.filter { it.chroma >= MIN_COLOUR_CHROMA }
    val hue = (vivid.firstOrNull() ?: hcts.first()).hue
    val tint = if (vivid.isEmpty()) 0.0 else 1.0
    return Theme(
        id = WALLPAPER_THEME_ID,
        name = name,
        dark = wallpaperScheme(Themes.Celestia.dark, hue, tint * DARK_STEP_CHROMA, vivid, DARK_COLOUR_TONES),
        light = wallpaperScheme(Themes.Celestia.light!!, hue, tint * LIGHT_STEP_CHROMA, vivid, LIGHT_COLOUR_TONES),
    )
}

private fun wallpaperScheme(
    measure: Scheme,
    hue: Double,
    stepChroma: Double,
    vivid: List<Hct>,
    tones: ClosedFloatingPointRange<Double>,
): Scheme = Scheme(
    steps = measure.steps.map { Hct.from(hue, stepChroma, it.lstar()).toColor() },
    inks = measure.inks.map { Hct.from(hue, stepChroma * INK_SHARE, it.lstar()).toColor() },
    colors = if (vivid.isEmpty()) measure.colors else vivid.map {
        Hct.from(it.hue, max(it.chroma, MIN_LEAD_CHROMA), it.tone.coerceIn(tones)).toColor()
    },
)

/** Below this a wallpaper colour is a grey with a cast, not a colour to draw with. */
private const val MIN_COLOUR_CHROMA = 12.0

/** A colour quieter than this reads as dirt on a filled control, so it is lifted to it. */
private const val MIN_LEAD_CHROMA = 32.0

private const val DARK_STEP_CHROMA = 6.0
private const val LIGHT_STEP_CHROMA = 4.0

/** How much of the ladder's tint the inks carry: enough to belong to it, not enough to colour text. */
private const val INK_SHARE = 0.4

private val DARK_COLOUR_TONES = 60.0..82.0
private val LIGHT_COLOUR_TONES = 35.0..50.0
