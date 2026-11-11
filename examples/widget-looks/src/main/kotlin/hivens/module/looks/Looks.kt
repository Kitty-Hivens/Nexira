package hivens.module.looks

import androidx.compose.ui.graphics.Color
import hivens.widget.api.ParticleContribution
import hivens.widget.api.ParticleMote
import hivens.widget.api.ParticlePalette
import hivens.widget.api.ThemeContribution
import hivens.widget.api.ThemeScheme
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin

/** A dark theme of deep violet with hot pink and cyan, in the spirit of an evening drive. */
class OutrunTheme : ThemeContribution {
    override val id = "outrun"
    override val label = "outrun"
    override val dark = ThemeScheme(
        steps = listOf(Color(0xFF140A28), Color(0xFF1F1038), Color(0xFF2A1748)),
        colors = listOf(Color(0xFFFF4DA6), Color(0xFF00E5FF), Color(0xFFFFC857), Color(0xFF8A5CFF)),
    )
}

/** Warm paper in light mode and a dark brown ground in dark mode, with ink-blue and rust accents. */
class PaperbackTheme : ThemeContribution {
    override val id = "paperback"
    override val label = "paperback"
    override val dark = ThemeScheme(
        steps = listOf(Color(0xFF1B1713), Color(0xFF261F19), Color(0xFF322A22)),
        colors = listOf(Color(0xFF7FA6D9), Color(0xFFD98A5F), Color(0xFFB6C47B)),
    )
    override val light = ThemeScheme(
        steps = listOf(Color(0xFFFBF6EC), Color(0xFFF2EBDC), Color(0xFFE8DFCB)),
        colors = listOf(Color(0xFF2F5C99), Color(0xFFA6502A), Color(0xFF5B6B22)),
        inks = listOf(Color(0xFF2B2418), Color(0xFF5A4F3D)),
    )
}

/** Warm points wandering in slow loops and pulsing, a few of them at a time bright. */
class FirefliesField : ParticleContribution {
    override val id = "fireflies"
    override val label = "fireflies"

    override fun palette(of: ParticlePalette): List<Color> = listOf(Color(0xFFFFE27A), Color(0xFFD4F26B), of.colors.first())

    override fun moteAt(i: Int, t: Float, w: Float, h: Float): ParticleMote {
        val phase = hash(i, 3)
        val loop = 14f + 18f * hash(i, 4)
        val angle = TAU * (t / loop + phase)
        val x = hash(i, 1) * w + sin(angle) * (18f + 30f * hash(i, 5))
        val y = hash(i, 2) * h + sin(angle * 1.7f + phase * TAU) * (12f + 24f * hash(i, 6))
        val glow = 0.5f + 0.5f * sin(TAU * (t / (3f + 4f * hash(i, 7)) + phase))
        return ParticleMote(
            x = wrap(x, w),
            y = wrap(y, h),
            radius = 1.2f + 1.6f * hash(i, 8),
            alpha = 0.05f + 0.75f * glow * glow * glow,
            colour = (hash(i, 9) * 1000).toInt(),
        )
    }
}

/** Small pieces falling and drifting sideways, in the theme's own accent colours. */
class ConfettiField : ParticleContribution {
    override val id = "confetti"
    override val label = "confetti"

    override fun palette(of: ParticlePalette): List<Color> = of.colors

    override fun moteAt(i: Int, t: Float, w: Float, h: Float): ParticleMote {
        val speed = 22f + 30f * hash(i, 4)
        val sway = sin(TAU * (t / (2f + 3f * hash(i, 5)) + hash(i, 3))) * 16f
        return ParticleMote(
            x = wrap(hash(i, 1) * w + sway, w),
            y = wrap(hash(i, 2) * h + speed * t, h),
            radius = 1.4f + 1.6f * hash(i, 6),
            alpha = 0.7f,
            colour = (hash(i, 7) * 1000).toInt(),
        )
    }
}

private const val TAU = (2.0 * PI).toFloat()

private fun wrap(v: Float, extent: Float): Float = v - extent * floor(v / extent)

/** A stable fraction in [0, 1) for mote [i] and property [k]. */
internal fun hash(i: Int, k: Int): Float {
    var x = i.toLong() * 0x9E3779B97F4A7C15uL.toLong() + k.toLong() * 0xC2B2AE3D27D4EB4FuL.toLong()
    x = (x xor (x ushr 33)) * 0xFF51AFD7ED558CCDuL.toLong()
    x = (x xor (x ushr 33)) * 0xC4CEB9FE1A85EC53uL.toLong()
    x = x xor (x ushr 33)
    return (x ushr 40).toFloat() / (1L shl 24).toFloat()
}
