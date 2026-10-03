package hivens.ui.widgets

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hivens.ui.FRAME_NANOS
import hivens.ui.theme.NxTheme
import hivens.ui.customization.CustomizationSettings
import hivens.ui.customization.LocalCustomization
import androidx.compose.runtime.CompositionLocalProvider
import hivens.widget.model.Entrance
import org.jetbrains.skia.Bitmap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * An arriving widget is not on screen before its arrival starts and is fully on
 * screen after it ends, and one told not to arrive is there from the first frame.
 * The first half is the one that matters most: a widget drawn and then hidden on
 * the next frame is a flicker on every screen change.
 */
@OptIn(ExperimentalComposeUiApi::class)
class WidgetEntranceRenderTest {

    private val ground = Color(0xFF000000)
    private val block = Color(0xFFFF00FF)

    private fun frames(entrance: Entrance, order: Int, reduceMotion: Boolean = false): Pair<Int, Int> {
        val scene = ImageComposeScene(100, 100, density = Density(1f)) {
            CompositionLocalProvider(LocalCustomization provides CustomizationSettings(reduceMotion = reduceMotion)) {
            NxTheme(dark = true) {
                Box(Modifier.fillMaxSize().background(ground)) {
                    PlayedWidgetEntrance(entrance, order, null) {
                        Box(Modifier.size(100.dp).background(block))
                    }
                }
            }
            }
        }
        try {
            val first = Bitmap.makeFromImage(scene.render(0)).getColor(50, 50)
            var t = 0L
            repeat(120) { t += FRAME_NANOS; scene.render(t).close(); Thread.sleep(8) }
            val last = Bitmap.makeFromImage(scene.render(t)).getColor(50, 50)
            return first to last
        } finally {
            scene.close()
        }
    }

    @Test
    fun `an arriving widget is invisible on its first frame and whole at the end`() {
        for (entrance in listOf(Entrance.Fade, Entrance.Rise, Entrance.Settle)) {
            val (first, last) = frames(entrance, order = 2)
            assertNotEquals(block.toArgb(), first, "$entrance showed before arriving")
            assertEquals(block.toArgb(), last, "$entrance never finished arriving")
        }
    }

    @Test
    fun `a widget told not to arrive is there from the first frame`() {
        val (first, _) = frames(Entrance.None, order = 3)
        assertEquals(block.toArgb(), first)
    }

    @Test
    fun `with reduced motion every widget is there from the first frame`() {
        for (entrance in Entrance.entries) {
            val (first, _) = frames(entrance, order = 2, reduceMotion = true)
            assertEquals(block.toArgb(), first, "$entrance arrived although motion was reduced")
        }
    }

    @Test
    fun `the stagger follows the place in the slot and stops growing`() {
        assertEquals(0, autoEntranceDelayMs(0))
        assertEquals(autoEntranceDelayMs(1) * 2, autoEntranceDelayMs(2))
        assertEquals(autoEntranceDelayMs(6), autoEntranceDelayMs(40))
    }
}
