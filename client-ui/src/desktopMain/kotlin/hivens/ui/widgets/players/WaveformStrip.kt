package hivens.ui.widgets.players

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hivens.ui.audio.Waveform
import hivens.ui.audio.WaveformCache
import hivens.ui.audio.resampleTo
import java.nio.file.Path
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * The envelope of [file], or null until it has been measured.
 *
 * Off the composition and cached, because measuring a track means decoding it:
 * about a second, once, and then free for as long as the file is the one loaded.
 * A caller draws the strip's own resting state while this is null rather than
 * drawing nothing, so the card does not change height when the answer lands.
 */
@Composable
internal fun rememberWaveform(file: Path?): Waveform? {
    val state = produceState<Waveform?>(initialValue = null, key1 = file) {
        value = null
        value = file?.let { WaveformCache.of(it) }
    }
    return state.value
}

/**
 * The envelope drawn as bars, with the played part inked.
 *
 * The bar count comes from the room on screen rather than from the envelope,
 * which carries far more buckets than any card is wide: drawing one bar per
 * bucket at this size would put several of them inside a pixel and turn the
 * outline into a smear. The envelope is reduced to the bars that fit, by the
 * same max-of-the-span rule that produced it, so what is dropped is resolution
 * and never a peak.
 *
 * With no envelope yet the bars are drawn flat at their floor height. That is
 * the resting state rather than a spinner: the strip already occupies its place
 * in the layout, so the card is the same height before and after, and a file
 * that never measures simply stays flat instead of leaving a hole.
 */
@Composable
internal fun WaveformStrip(
    waveform: Waveform?,
    fraction: Float,
    played: Color,
    remaining: Color,
    modifier: Modifier = Modifier,
    barWidth: Dp = 3.dp,
    gap: Dp = 1.dp,
    onSeekFraction: ((Float) -> Unit)? = null,
) {
    val density = LocalDensity.current
    val slotPx = with(density) { (barWidth + gap).toPx() }.coerceAtLeast(1f)
    val barPx = with(density) { barWidth.toPx() }.coerceAtLeast(1f)
    val barsMemo = remember(waveform) { BarsMemo(waveform) }

    Canvas(modifier.seekAlong(vertical = false, onSeekFraction = onSeekFraction)) {
        val count = (size.width / slotPx).toInt().coerceAtLeast(1)
        val bars = barsMemo.bars(count)
        val floor = size.height * FLOOR_SHARE
        val cut = count * fraction.coerceIn(0f, 1f)
        val radius = CornerRadius(barPx / 2f, barPx / 2f)
        for (i in 0 until count) {
            val height = (bars[i] * size.height).coerceAtLeast(floor)
            drawRoundRect(
                color = if (i < cut) played else remaining,
                topLeft = Offset(i * slotPx + (slotPx - barPx) / 2f, (size.height - height) / 2f),
                size = Size(barPx, height),
                cornerRadius = radius,
            )
        }
    }
}

/**
 * The same envelope stood on its end, for a portrait card.
 *
 * A separate composable rather than an axis flag on the one above, because what
 * differs is not only which way the bars run: they grow from one edge here
 * instead of from the middle and the played part is the top rather than the
 * left.
 *
 * It does take a drag. The worry was that a vertical scrub inside a rail is a
 * gesture the rail wants for scrolling, but a rail scrolls on the wheel and a
 * drag that starts on a child is the child's: what the caution actually bought
 * was a measure people could see and could not move.
 */
@Composable
internal fun WaveformColumn(
    waveform: Waveform?,
    fraction: Float,
    played: Color,
    remaining: Color,
    modifier: Modifier = Modifier,
    barHeight: Dp = 3.dp,
    gap: Dp = 1.dp,
    onSeekFraction: ((Float) -> Unit)? = null,
) {
    val density = LocalDensity.current
    val slotPx = with(density) { (barHeight + gap).toPx() }.coerceAtLeast(1f)
    val barPx = with(density) { barHeight.toPx() }.coerceAtLeast(1f)
    val barsMemo = remember(waveform) { BarsMemo(waveform) }

    Canvas(modifier.seekAlong(vertical = true, onSeekFraction = onSeekFraction)) {
        val count = (size.height / slotPx).toInt().coerceAtLeast(1)
        val bars = barsMemo.bars(count)
        val floor = size.width * FLOOR_SHARE
        val cut = count * fraction.coerceIn(0f, 1f)
        val radius = CornerRadius(barPx / 2f, barPx / 2f)
        for (i in 0 until count) {
            val length = (bars[i] * size.width).coerceAtLeast(floor)
            drawRoundRect(
                color = if (i < cut) played else remaining,
                topLeft = Offset(0f, i * slotPx),
                size = Size(length, barPx),
                cornerRadius = radius,
            )
        }
    }
}

/**
 * The envelope bent into a ring, radiating outward from [innerRadius].
 *
 * A third composable rather than a mode on the first, because almost nothing is
 * shared once the bars stop being axis aligned: each one is a line at its own
 * angle, the played part is an arc rather than a prefix of a row, and the count
 * comes from the circumference rather than from a width. What it does share is
 * the rule that the count comes from the room available, which matters more here
 * than anywhere: bars crowded onto a small circle overlap into a solid disc.
 *
 * The ring starts at twelve o'clock and runs clockwise, which is where a listener
 * expects a played share to start and the direction they expect it to travel.
 */
@Composable
internal fun WaveformRing(
    waveform: Waveform?,
    fraction: Float,
    played: Color,
    remaining: Color,
    innerRadius: Float,
    modifier: Modifier = Modifier,
    strokeWidth: Dp = 2.dp,
    spacing: Dp = 3.dp,
    /**
     * How much of the room outside the label the loudest bar may take. Short of
     * the whole of it on purpose: bars that reach the edge read as rays coming
     * off a sun rather than as a ring around a disc, and the shape stops being an
     * object and becomes a burst.
     */
    reach: Float = 0.78f,
) {
    val density = LocalDensity.current
    val strokePx = with(density) { strokeWidth.toPx() }.coerceAtLeast(1f)
    val spacingPx = with(density) { spacing.toPx() }.coerceAtLeast(1f)
    val barsMemo = remember(waveform) { BarsMemo(waveform) }

    Canvas(modifier) {
        val centreX = size.width / 2f
        val centreY = size.height / 2f
        val inner = innerRadius.coerceAtLeast(1f)
        val room = ((size.minDimension / 2f - inner) * reach.coerceIn(0.1f, 1f)).coerceAtLeast(1f)
        val count = ((2.0 * PI * inner) / spacingPx).toInt().coerceIn(12, MAX_RING_BARS)
        val bars = barsMemo.bars(count)
        val cut = count * fraction.coerceIn(0f, 1f)
        val floor = room * FLOOR_SHARE
        for (i in 0 until count) {
            val angle = (-90.0 + i * 360.0 / count) * PI / 180.0
            val cos = cos(angle).toFloat()
            val sin = sin(angle).toFloat()
            val length = (bars[i] * room).coerceAtLeast(floor)
            drawLine(
                color = if (i < cut) played else remaining,
                start = Offset(centreX + cos * inner, centreY + sin * inner),
                end = Offset(centreX + cos * (inner + length), centreY + sin * (inner + length)),
                strokeWidth = strokePx,
                cap = StrokeCap.Round,
            )
        }
    }
}

/**
 * A ceiling on the ring's bars.
 *
 * A large disc would otherwise ask for more of them than the envelope has
 * buckets, and resampling upward repeats values, so past this the ring stops
 * gaining detail and starts drawing the same bar twice at slightly different
 * angles.
 */
private const val MAX_RING_BARS = 240

/** The envelope reduced to [count] bars, or a flat resting row when there is none. */
private fun Waveform?.bars(count: Int): FloatArray {
    if (this == null) return FloatArray(count)
    val source = FloatArray(size) { this[it] }
    return resampleTo(source, count)
}

/**
 * Holds one envelope's resampled bars, recomputing only when the bar count changes.
 *
 * The reduction is fixed for a given envelope and count: the envelope belongs to
 * the track and the count follows the card's width, so neither moves between
 * frames. Doing it inside the draw lambda allocated a fresh FloatArray on every
 * draw, which a video wallpaper turns into steady churn by holding the draw clock
 * awake at the display refresh. A caller remembers one of these keyed on the
 * envelope, so a new track starts from an empty memo.
 */
private class BarsMemo(private val waveform: Waveform?) {
    private var count = -1
    private var cached = FloatArray(0)
    fun bars(barCount: Int): FloatArray {
        if (barCount != count) {
            count = barCount
            cached = waveform.bars(barCount)
        }
        return cached
    }
}

/**
 * How tall a bar is where the track is silent.
 *
 * Not zero: a gap in the middle of a strip reads as a hole in the widget rather
 * than as a quiet passage, and the lead-in of a great many tracks is silent.
 */
private const val FLOOR_SHARE = 0.08f

/**
 * Seeking round a ring: a press or a drag lands where the angle points.
 *
 * Twelve o'clock is the start and it runs clockwise, matching what the ring
 * draws, so the position follows the finger rather than the arithmetic.
 *
 * A press has to LAND on the ring, and only a press. Nearer the middle than
 * [innerFraction] is the transport's own ground, and past the outer edge is
 * outside the object altogether, since both shapes that use this are round and are
 * laid out in a square: without the second test a click on nothing, in the corner
 * beside a token, moved the track.
 *
 * Neither test is applied once a gesture is under way. The whole point of a rotary
 * control is that it follows the angle for as long as the button is held.
 */
internal fun Modifier.seekByAngle(
    innerFraction: Float,
    onSeekFraction: ((Float) -> Unit)?,
): Modifier {
    if (onSeekFraction == null) return this
    return pointerInput(onSeekFraction, innerFraction) {
        val centre = Offset(size.width / 2f, size.height / 2f)
        val radius = size.width.coerceAtMost(size.height) / 2f
        val inner = radius * innerFraction

        /** Whether a point is on the band the ring occupies. Asked of a press only. */
        fun onBand(at: Offset): Boolean {
            val reach = hypot(at.x - centre.x, at.y - centre.y)
            return reach >= inner && reach <= radius
        }

        fun report(at: Offset) {
            // atan2 answers from three o'clock and counts anticlockwise on a
            // screen's y-down axes, so a quarter turn puts zero at the top and the
            // sweep runs the way the arc is drawn.
            val turns = (atan2(at.y - centre.y, at.x - centre.x) / (2.0 * PI) + 0.25).toFloat()
            onSeekFraction(((turns % 1f) + 1f) % 1f)
        }

        awaitPointerEventScope {
            while (true) {
                // Unconsumed only: the transport in the middle and the overflow on
                // the ring are children drawn over this, and a press one of them
                // took is not also a seek.
                val down = awaitFirstDown(requireUnconsumed = true)
                // The band decides whether a gesture STARTS, and nothing after
                // that. A rotary control follows the angle for as long as the
                // button is held, and a hand sweeping round a small disc leaves the
                // band constantly: asked on every move, the position froze wherever
                // the pointer crossed an edge, with nothing on screen to say the
                // drag had been dropped. Once a press has landed on the ring, every
                // angle it reaches afterwards is meant.
                if (!onBand(down.position)) continue
                report(down.position)
                do {
                    val event = awaitPointerEvent()
                    event.changes.firstOrNull()?.let { report(it.position) }
                } while (event.changes.any { it.pressed })
            }
        }
    }
}

/**
 * Seeking along a strip: the press lands, and the drag carries on from it.
 *
 * One gesture rather than a tap detector beside a drag detector. Two of them on
 * the same element race for the press, and the drag wins often enough that a
 * plain click on the strip did nothing perhaps half the time. There is no tap
 * here at all: a press is the first report and every move after it is another,
 * which is the same thing a tap would have been and cannot be stolen.
 */
internal fun Modifier.seekAlong(
    vertical: Boolean,
    onSeekFraction: ((Float) -> Unit)?,
): Modifier {
    if (onSeekFraction == null) return this
    return pointerInput(onSeekFraction, vertical) {
        fun report(at: Offset) {
            val span = (if (vertical) size.height else size.width).toFloat()
            if (span <= 0f) return
            onSeekFraction(((if (vertical) at.y else at.x) / span).coerceIn(0f, 1f))
        }
        awaitPointerEventScope {
            while (true) {
                val down = awaitFirstDown(requireUnconsumed = true)
                report(down.position)
                do {
                    val event = awaitPointerEvent()
                    event.changes.firstOrNull()?.let { report(it.position) }
                } while (event.changes.any { it.pressed })
            }
        }
    }
}
