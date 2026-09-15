package hivens.ui.widgets.sample.players

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import kotlin.math.cos
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

    val seekable = onSeekFraction != null
    val gestures = if (!seekable) Modifier else Modifier.pointerInput(onSeekFraction) {
        val width = size.width.toFloat()
        detectTapGestures { onSeekFraction!!((it.x / width).coerceIn(0f, 1f)) }
    }.then(
        Modifier.pointerInput(onSeekFraction) {
            val width = size.width.toFloat()
            detectHorizontalDragGestures { change, _ ->
                onSeekFraction!!((change.position.x / width).coerceIn(0f, 1f))
            }
        },
    )

    Canvas(modifier.then(gestures)) {
        val count = (size.width / slotPx).toInt().coerceAtLeast(1)
        // Resampling per draw rather than per recomposition: the count depends on
        // the measured width, which is not known until here, and the reduction is
        // a single pass over a few hundred floats.
        val bars = waveform.bars(count)
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
 * instead of from the middle, the played part is the top rather than the left,
 * and there is no drag, since a vertical scrub in a rail is a gesture the rail
 * itself wants.
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
) {
    val density = LocalDensity.current
    val slotPx = with(density) { (barHeight + gap).toPx() }.coerceAtLeast(1f)
    val barPx = with(density) { barHeight.toPx() }.coerceAtLeast(1f)

    Canvas(modifier) {
        val count = (size.height / slotPx).toInt().coerceAtLeast(1)
        val bars = waveform.bars(count)
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

    Canvas(modifier) {
        val centreX = size.width / 2f
        val centreY = size.height / 2f
        val inner = innerRadius.coerceAtLeast(1f)
        val room = ((size.minDimension / 2f - inner) * reach.coerceIn(0.1f, 1f)).coerceAtLeast(1f)
        val count = ((2.0 * PI * inner) / spacingPx).toInt().coerceIn(12, MAX_RING_BARS)
        val bars = waveform.bars(count)
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
 * How tall a bar is where the track is silent.
 *
 * Not zero: a gap in the middle of a strip reads as a hole in the widget rather
 * than as a quiet passage, and the lead-in of a great many tracks is silent.
 */
private const val FLOOR_SHARE = 0.08f
