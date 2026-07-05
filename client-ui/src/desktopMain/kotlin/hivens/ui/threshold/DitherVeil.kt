package hivens.ui.threshold

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asComposeShader
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder

/**
 * The threshold's dark veil as a runtime shader: an ordered-dither (Bayer)
 * dissolve, the way 8/16-bit games faded to and from black -- the darkness
 * lifts pixel-cell by pixel-cell on the same grid the bar lives on, instead
 * of a uniform alpha ramp.
 *
 * The effect compiles once; a compile failure (driver quirk, SkSL rejection)
 * degrades to null and the caller falls back to a plain alpha rect -- the
 * shader is presentation, never a dependency.
 */
object DitherVeil {

    // progress: 0 = fully dark, 1 = fully lifted. cell: dither cell in px.
    // Compact recursive Bayer threshold (the classic fract-based construction);
    // three octaves give an effective 8x8 matrix -- enough steps that the
    // dissolve reads as texture, not as four visible bands.
    private const val SKSL = """
        uniform float progress;
        uniform float cell;
        uniform float3 veilColor;

        float bayer2(float2 a) {
            a = floor(a);
            return fract(a.x / 2.0 + a.y * a.y * 0.75);
        }

        half4 main(float2 coord) {
            float2 p = floor(coord / cell);
            float t = bayer2(p * 0.25) * 0.0625 + bayer2(p * 0.5) * 0.25 + bayer2(p);
            t = fract(t);
            if (progress > t) {
                return half4(0.0);
            }
            return half4(veilColor, 1.0);
        }
    """

    private val effect: RuntimeEffect? = runCatching { RuntimeEffect.makeForShader(SKSL) }.getOrNull()

    val available: Boolean get() = effect != null

    /** A brush drawing the [color] veil at [progress] lifted, on a [cellPx] grid. */
    fun brush(progress: Float, cellPx: Float, color: Color): ShaderBrush? {
        val fx = effect ?: return null
        return object : ShaderBrush() {
            override fun createShader(size: Size): Shader {
                val b = RuntimeShaderBuilder(fx)
                b.uniform("progress", progress)
                b.uniform("cell", cellPx)
                b.uniform("veilColor", color.red, color.green, color.blue)
                return b.makeShader().asComposeShader()
            }
        }
    }
}
