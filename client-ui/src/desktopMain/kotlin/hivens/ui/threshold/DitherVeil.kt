package hivens.ui.threshold

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asComposeShader
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder

/**
 * The threshold's dark veil as a runtime shader: an ordered-dither (Bayer)
 * dissolve, the way 8/16-bit games faded to and from black -- darkness leaves
 * cell by cell on the same grid the bar lives on, never as a uniform alpha ramp.
 *
 * Two modes share one effect:
 *  - uniform: the whole field dissolves by [brush]'s progress (the fallback
 *    contract, kept for degraded paths);
 *  - radial: [waveBrush]'s front sweeps outward from an origin, each cell
 *    clearing once the front passes its Bayer-jittered distance -- the exit
 *    beat's wave, chunky on the pixel grid.
 *
 * The effect compiles once; a compile failure (driver quirk, SkSL rejection)
 * degrades to null and the caller falls back to a plain alpha rect -- the
 * shader is presentation, never a dependency.
 */
object DitherVeil {

    // cell: dither cell in px. mode < 0.5 -> uniform (progress 0 dark..1 lifted);
    // else radial (origin/frontPx/bandPx in px). Cell distance is measured to the
    // CELL CENTER so the ring quantizes to the same grid as the bar.
    private const val SKSL = """
        uniform float mode;
        uniform float progress;
        uniform float2 origin;
        uniform float frontPx;
        uniform float bandPx;
        uniform float cell;
        uniform float3 veilColor;

        float bayer2(float2 a) {
            a = floor(a);
            return fract(a.x / 2.0 + a.y * a.y * 0.75);
        }

        float bayer(float2 p) {
            return fract(bayer2(p * 0.25) * 0.0625 + bayer2(p * 0.5) * 0.25 + bayer2(p));
        }

        half4 main(float2 coord) {
            float2 p = floor(coord / cell);
            float t = bayer(p);
            if (mode < 0.5) {
                if (progress > t) {
                    return half4(0.0);
                }
                return half4(veilColor, 1.0);
            }
            float2 cellCenter = (p + 0.5) * cell;
            float d = distance(cellCenter, origin) + (t - 0.5) * bandPx;
            if (frontPx > d) {
                return half4(0.0);
            }
            return half4(veilColor, 1.0);
        }
    """

    private val effect: RuntimeEffect? = runCatching { RuntimeEffect.makeForShader(SKSL) }.getOrNull()

    val available: Boolean get() = effect != null

    /** A brush drawing the [color] veil at [progress] lifted uniformly, on a [cellPx] grid. */
    fun brush(progress: Float, cellPx: Float, color: Color): ShaderBrush? {
        val fx = effect ?: return null
        return object : ShaderBrush() {
            override fun createShader(size: Size): Shader {
                val b = RuntimeShaderBuilder(fx)
                b.uniform("mode", 0f)
                b.uniform("progress", progress)
                b.uniform("origin", 0f, 0f)
                b.uniform("frontPx", 0f)
                b.uniform("bandPx", 0f)
                b.uniform("cell", cellPx)
                b.uniform("veilColor", color.red, color.green, color.blue)
                return b.makeShader().asComposeShader()
            }
        }
    }

    /**
     * A brush drawing the [color] veil with a radial dissolve front at
     * [frontPx] from [origin]. INVARIANT the caller must uphold: at the end of
     * the wave, frontPx must exceed the farthest corner distance plus
     * [bandPx] plus one cell, so every jittered cell has cleared before the
     * overlay unmounts -- anything short of that pops residual dark cells on
     * the removal frame.
     */
    fun waveBrush(origin: Offset, frontPx: Float, bandPx: Float, cellPx: Float, color: Color): ShaderBrush? {
        val fx = effect ?: return null
        return object : ShaderBrush() {
            override fun createShader(size: Size): Shader {
                val b = RuntimeShaderBuilder(fx)
                b.uniform("mode", 1f)
                b.uniform("progress", 0f)
                b.uniform("origin", origin.x, origin.y)
                b.uniform("frontPx", frontPx)
                b.uniform("bandPx", bandPx)
                b.uniform("cell", cellPx)
                b.uniform("veilColor", color.red, color.green, color.blue)
                return b.makeShader().asComposeShader()
            }
        }
    }
}
