package hivens.module.screenfx

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import hivens.widget.api.ModuleValues
import hivens.widget.api.ScreenEffect
import hivens.widget.api.ScreenEffectState
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.RuntimeEffect
import java.util.ServiceLoader
import javax.swing.SwingUtilities
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The shaders compile on this Skia, the module offers what it says it does, and an effect changes the picture only when asked to. */
@OptIn(ExperimentalComposeUiApi::class)
class ScreenEffectsTest {

    private val sources = mapOf(
        "trip" to TripEffect.effect,
        "retro" to RetroEffect.effect,
        "glitch" to GlitchEffect.effect,
    )

    @Test
    fun `the service file names three effects with distinct ids`() {
        val loaded = ServiceLoader.load(ScreenEffect::class.java).toList()

        assertEquals(setOf("screenfx.trip", "screenfx.retro", "screenfx.glitch"), loaded.map { it.id }.toSet())
        assertTrue(loaded.all { it.label.isNotBlank() })
    }

    @Test
    fun `every label and setting label is a key the module has words for in every language it ships`() {
        val tables = listOf("en", "ru", "de", "ja").associateWith { tag ->
            java.util.Properties().apply {
                ScreenEffectsTest::class.java.classLoader.getResourceAsStream("i18n/$tag.properties")!!.reader(Charsets.UTF_8).use { load(it) }
            }
        }
        val keys = ServiceLoader.load(ScreenEffect::class.java).flatMap { listOf(it.label) + it.settings.map { s -> s.label } }.toSet()
        tables.forEach { (tag, table) ->
            assertEquals(emptySet(), keys - table.stringPropertyNames(), "keys missing in $tag")
        }
    }

    @Test
    fun `every shader compiles on this Skia`() {
        sources.forEach { (name, effect) ->
            val source = effect.javaClass.getDeclaredField("source").apply { isAccessible = true }.get(effect) as String
            RuntimeEffect.makeForShader(source) // throws with the compiler's message when it does not
            assertTrue(source.isNotBlank(), name)
        }
    }

    @Test
    fun `at zero strength the interface is drawn as it is`() {
        sources.values.forEach { effect ->
            assertEquals(render(null).toList(), render(effect, strength = 0f).toList(), effect.id)
        }
    }

    @Test
    fun `at full strength every effect changes the picture`() {
        sources.values.forEach { effect ->
            assertNotEquals(render(null).toList(), render(effect, strength = 1f, seconds = 1).toList(), effect.id)
        }
    }

    /** What the interface draws, optionally through [effect], as raw pixels. */
    private fun render(effect: ScreenEffect?, strength: Float = 1f, seconds: Long = 0): ByteArray {
        var bytes = ByteArray(0)
        SwingUtilities.invokeAndWait {
            val scene = ImageComposeScene(120, 80, density = Density(1f)) {
                val picture: @Composable () -> Unit = {
                    Canvas(Modifier.fillMaxSize()) {
                        drawRect(Brush.linearGradient(listOf(Color.Red, Color.Green, Color.Blue)))
                    }
                }
                if (effect == null) picture() else effect.Apply(ScreenEffectState(ModuleValues(effect.settings, mapOf("strength" to strength.toString())), reducedMotion = false), picture)
            }
            try {
                // The clock starts at the first frame, so the later frames are what moves.
                scene.render(0L)
                bytes = scene.render(seconds * 1_000_000_000L).use { image: Image -> image.encodeToData(EncodedImageFormat.PNG)!!.bytes }
            } finally {
                scene.close()
            }
        }
        return bytes
    }
}
