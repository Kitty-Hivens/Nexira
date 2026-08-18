package hivens.module.pixelplayer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * The module's own look. Not read from a theme, and that is the point of the
 * exercise -- a contributed widget has to be able to decide how it looks without
 * the launcher's design system granting it anything.
 *
 * Solid throughout. No alpha, no blur, no gradient: a pixel surface reads as a
 * pixel surface because its edges are exactly where they are, and a translucent
 * one takes its colour from whatever is behind it.
 */
internal object PixelSkin {
    val panel = Color(0xFF17171C)
    val panelEdge = Color(0xFF3A3A46)
    val well = Color(0xFF0E0E12)
    val text = Color(0xFFE6E6EE)
    val textDim = Color(0xFF8B8B9A)
    val accent = Color(0xFF6ED08A)

    /**
     * The unplayed part of the track, and deliberately NOT a dimmer accent.
     *
     * A bar whose empty cells are a faded version of its full ones reads as
     * full: at a glance the eye takes the whole strip as coloured and the
     * boundary as a highlight. The launcher already had that defect once, on a
     * volume bar that idled at maximum and looked like a finished track.
     */
    val trackEmpty = Color(0xFF2A2A33)
    val accentDim = Color(0xFF2E5C3D)
    val danger = Color(0xFFD06E6E)

    /**
     * Two families on purpose.
     *
     * A track name is arbitrary text -- it arrives from a container tag in
     * whatever script the release used, and a monospace face does not carry
     * Japanese, so a library of game soundtracks renders as boxes. Titles and
     * artists therefore go through the platform's default family, which falls
     * back per script.
     *
     * The timecode, the counters and the glyphs stay monospace: those are the
     * parts where fixed advance is the look, and they are ASCII by construction.
     */
    val text_ = FontFamily.Default
    val mono = FontFamily.Monospace

    val titleSize = 15.sp
    val bodySize = 12.sp
    val timeSize = 12.sp
    val glyphSize = 16.sp
    /** The step controls are secondary: same family, smaller voice. */
    val stepGlyphSize = 11.sp

    /** Hard edge, one step. Nothing here rounds. */
    val edge = 2.dp
    val gap = 10.dp
    val pad = 12.dp
    val artSide = 72.dp
    val buttonSide = 28.dp
    val playSide = 46.dp
    val barHeight = 12.dp

    /**
     * Media glyphs, deliberately from the text-presentation set.
     *
     * The obvious ones -- pause, previous-track, next-track at U+23F8, U+23EE,
     * U+23ED -- default to emoji presentation, which renders as a colour picture
     * out of a different font and is out of place in a launcher. These are
     * ordinary glyphs that any monospace face carries.
     */
    const val PLAY = "▶"      // BLACK RIGHT-POINTING TRIANGLE
    const val PAUSE = "▮▮" // two BLACK VERTICAL RECTANGLEs
    const val PREV = "|◀"
    const val NEXT = "▶|"
    const val NOTE = "♪"      // EIGHTH NOTE, the artwork placeholder
}

/** `73_000` -> `"1:13"`. Unknown length reads as `--:--` rather than as zero. */
internal fun clock(ms: Long): String {
    if (ms < 0L) return "--:--"
    val total = ms / 1000
    return "%d:%02d".format(total / 60, total % 60)
}
