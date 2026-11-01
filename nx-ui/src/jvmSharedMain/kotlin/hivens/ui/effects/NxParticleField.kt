package hivens.ui.effects

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalWindowInfo
import hivens.ui.customization.LocalCustomization
import hivens.ui.theme.LocalScheme
import hivens.ui.theme.NxInk
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin
import kotlin.time.Duration.Companion.milliseconds

/**
 * What a field of particles is: a closed set of characters, like an arrival.
 *
 * Each one is a rule for where mote number i is at time t, and nothing else. There
 * is no simulation to step and no state per mote, so a frame costs one pass over the
 * motes and allocates nothing, the same field is drawn at any moment it is asked
 * for, and a later timeline can scrub it.
 */
enum class ParticleField {
    /** Motes hanging in the air, drifting and breathing. */
    Dust,

    /** Sparks rising and fading as they climb, in the theme's own colours. */
    Embers,

    /** Flakes falling and swaying. */
    Snow,

    /** Points fixed in place that twinkle. */
    Stars,
}

/** How many motes a field holds for its area. Three steps, so a field is never thick enough to read as noise. */
enum class ParticleDensity(internal val areaPerMoteDp: Float) {
    Sparse(9_000f),
    Even(5_000f),
    Rich(2_500f),
}

/**
 * A field of particles filling [modifier]'s bounds, in the theme's colours.
 *
 * Decor, and drawn as decor: in its own layer, from its own clock, invalidating its
 * own draw and nothing else, so the content in front of it neither recomposes nor
 * redraws for it. Colours come from the active scheme and follow a theme change.
 */
@Composable
fun NxParticleField(
    field: ParticleField,
    modifier: Modifier = Modifier,
    density: ParticleDensity = ParticleDensity.Even,
) {
    val scheme = LocalScheme.current.scheme
    val ink = NxInk.main
    val quiet = NxInk.quiet
    val palette: List<Color> = remember(scheme, field, ink, quiet) {
        when (field) {
            ParticleField.Dust -> listOf(quiet)
            ParticleField.Snow -> listOf(ink)
            ParticleField.Stars -> listOf(ink, ink, ink, scheme.colors.first())
            ParticleField.Embers -> scheme.colors
        }
    }
    // Named apart from the draw scope's own density, which is the screen's.
    val perArea = density
    // Held on its first frame when the person asked for less movement: the field
    // still decorates, it just stops drifting, and stops costing a frame a frame.
    val still = LocalCustomization.current.reduceMotion
    // The two bounds the skin view puts on its own loop, for the same reasons.
    // Asking for every frame keeps the compositor redrawing at the panel's rate,
    // so the loop sleeps a floor first and asks for a frame second. And it stops
    // while the window is not focused, which with the launcher behind a running
    // game is the whole session: a field nobody is looking at is not drawn. It
    // carries on from where it stood when focus comes back.
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    var nanos by remember { mutableLongStateOf(0L) }
    LaunchedEffect(still, windowFocused) {
        if (still || !windowFocused) return@LaunchedEffect
        val start = withFrameNanos { it } - nanos
        while (true) {
            delay(MIN_ADVANCE_MS.milliseconds)
            withFrameNanos { nanos = it - start }
        }
    }
    Canvas(modifier.graphicsLayer()) {
        // Read here and only here, so a frame is a draw of this layer and not a
        // recomposition of anything.
        val t = nanos / 1_000_000_000f
        val w = size.width / this.density
        val h = size.height / this.density
        if (w <= 0f || h <= 0f) return@Canvas
        val count = moteCount(w, h, perArea)
        for (i in 0 until count) {
            val m = moteAt(field, i, t, w, h)
            if (m.alpha <= 0.01f) continue
            drawCircle(
                color = palette[m.colour % palette.size].copy(alpha = m.alpha),
                radius = m.radius * this.density,
                center = Offset(m.x * this.density, m.y * this.density),
            )
        }
    }
}

/** One mote at one moment, in dp. [colour] picks from the field's palette. */
internal data class Mote(val x: Float, val y: Float, val radius: Float, val alpha: Float, val colour: Int)

/** How many motes [density] puts in a [w] by [h] dp area, held under a ceiling a frame can always afford. */
internal fun moteCount(w: Float, h: Float, density: ParticleDensity): Int =
    ((w * h) / density.areaPerMoteDp).toInt().coerceIn(0, MAX_MOTES)

/**
 * Where mote [i] of [field] is at [t] seconds in a [w] by [h] dp area.
 *
 * Every property comes from a hash of the mote's number, so the same mote is the
 * same mote on every frame and on every run. Travel wraps around the area, which
 * keeps the count constant without spawning anything.
 */
internal fun moteAt(field: ParticleField, i: Int, t: Float, w: Float, h: Float): Mote {
    val rx = rand(i, 1)
    val ry = rand(i, 2)
    val rs = rand(i, 3)
    val rp = rand(i, 4)
    val rv = rand(i, 5)
    val colour = (rand(i, 6) * 1_000).toInt()
    return when (field) {
        ParticleField.Dust -> {
            val vx = (rv - 0.5f) * 12f
            val vy = (rp - 0.5f) * 8f
            val wobble = sin(TAU * (t / (6f + 6f * rs) + rp)) * 10f
            val breath = 0.5f + 0.5f * sin(TAU * (t / (5f + 5f * rv) + rs))
            Mote(
                x = wrap(rx * w + vx * t + wobble, w),
                y = wrap(ry * h + vy * t, h),
                radius = 0.8f + 1.2f * rs,
                alpha = 0.08f + 0.22f * breath,
                colour = colour,
            )
        }
        ParticleField.Embers -> {
            val speed = 12f + 18f * rv
            val y = wrap(ry * h - speed * t, h)
            val sway = sin(TAU * (t / (3f + 3f * rs) + rp)) * 14f
            // Fades as it climbs, so a spark burns out rather than leaving the top edge.
            val life = (y / h).coerceIn(0f, 1f)
            Mote(
                x = wrap(rx * w + sway, w),
                y = y,
                radius = 1f + 1.5f * rs,
                alpha = 0.55f * life * life,
                colour = colour,
            )
        }
        ParticleField.Snow -> {
            val speed = 10f + 18f * rv
            val sway = sin(TAU * (t / (4f + 4f * rs) + rp)) * 20f
            Mote(
                x = wrap(rx * w + sway, w),
                y = wrap(ry * h + speed * t, h),
                radius = 1f + 2f * rs,
                alpha = 0.25f + 0.4f * rp,
                colour = colour,
            )
        }
        ParticleField.Stars -> {
            val pulse = 0.5f + 0.5f * sin(TAU * (t / (2.5f + 4f * rv) + rp))
            Mote(
                x = rx * w,
                y = ry * h,
                radius = 0.6f + 1f * rs,
                alpha = 0.08f + 0.6f * pulse * pulse * pulse,
                colour = colour,
            )
        }
    }
}

private fun wrap(v: Float, extent: Float): Float = v - extent * floor(v / extent)

/** A stable fraction in [0, 1) for mote [i] and property [k]. A murmur finaliser over the pair. */
internal fun rand(i: Int, k: Int): Float {
    var x = i.toLong() * 0x9E3779B97F4A7C15uL.toLong() + k.toLong() * 0xC2B2AE3D27D4EB4FuL.toLong()
    x = (x xor (x ushr 33)) * 0xFF51AFD7ED558CCDuL.toLong()
    x = (x xor (x ushr 33)) * 0xC4CEB9FE1A85EC53uL.toLong()
    x = x xor (x ushr 33)
    return ((x ushr 40).toFloat() / (1L shl 24).toFloat())
}

private const val TAU = (2.0 * PI).toFloat()

/** Floor on the interval between two advances of the field's clock, about thirty a second. */
private const val MIN_ADVANCE_MS = 33L

/** A ceiling on motes per field: a frame at this count is one cheap pass, a full window at Rich is under it. */
internal const val MAX_MOTES = 700
