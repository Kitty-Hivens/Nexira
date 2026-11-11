package hivens.module.screenfx

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import hivens.widget.api.ModuleSetting
import hivens.widget.api.ScreenEffect
import hivens.widget.api.ScreenEffectState
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder

/**
 * A screen effect that is one SkSL fragment shader over the interface.
 *
 * The shader declares `uniform shader content`, which is the interface as drawn, and the three
 * uniforms [RESOLUTION], [TIME] and [STRENGTH]. It runs per pixel on the frame the interface has
 * already been drawn into, so what it costs is what a full window filter costs.
 *
 * A shader the device refuses to compile leaves the interface drawn plainly instead of failing.
 */
internal class ShaderEffect(
    override val id: String,
    override val label: String,
    private val source: String,
) : ScreenEffect {

    override val settings: List<ModuleSetting> = listOf(
        ModuleSetting.Amount(STRENGTH, "strength", default = 0.5f),
    )

    @Composable
    override fun Apply(state: ScreenEffectState, content: @Composable () -> Unit) {
        val effect = remember(source) { runCatching { RuntimeEffect.makeForShader(source) }.getOrNull() }
        if (effect == null) {
            content()
            return
        }
        val builder = remember(effect) { RuntimeShaderBuilder(effect) }
        var seconds by remember { mutableFloatStateOf(0f) }
        // Held still while the interface asks for stillness: the look stays, the movement goes.
        LaunchedEffect(state.reducedMotion) {
            if (state.reducedMotion) {
                seconds = 0f
            } else {
                val start = withFrameNanos { it }
                while (true) {
                    withFrameNanos { seconds = ((it - start) / NANOS_PER_SECOND) % PERIOD_SECONDS }
                }
            }
        }
        Box(
            Modifier.graphicsLayer {
                builder.uniform(RESOLUTION, size.width, size.height)
                builder.uniform(TIME, seconds)
                builder.uniform(STRENGTH, state.values.amount(STRENGTH))
                renderEffect = ImageFilter.makeRuntimeShader(builder, CHILD, null).asComposeRenderEffect()
            },
        ) { content() }
    }

    private companion object {
        const val CHILD = "content"
        const val RESOLUTION = "resolution"
        const val TIME = "time"
        const val STRENGTH = "strength"

        const val NANOS_PER_SECOND = 1_000_000_000f

        /** Keeps the clock a shader reads small, where a float still has room for a fraction of a frame. */
        const val PERIOD_SECONDS = 3_600f
    }
}
