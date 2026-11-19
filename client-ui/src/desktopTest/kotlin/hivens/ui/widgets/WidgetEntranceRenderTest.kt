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
import hivens.themes.Themes
import hivens.ui.FRAME_NANOS
import hivens.ui.theme.NxThemed
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
 *
 * Where the look holds decoration still, an arrival its widget declares and the
 * slot's stagger are held still, and a character or a delay the node names plays
 * as set: the widget is stronger than the look.
 */
@OptIn(ExperimentalComposeUiApi::class)
class WidgetEntranceRenderTest {

    private val ground = Color(0xFF000000)
    private val block = Color(0xFFFF00FF)

    /**
     * The colour at the block's centre on the first frame, after [early] milliseconds of frames, and at
     * the end. A delay waits on the clock, so the frames before [early] are drawn at once, or [paced]
     * like the ones after it.
     */
    private fun frames(
        entrance: Entrance,
        order: Int,
        reduceMotion: Boolean = false,
        onNode: Boolean = false,
        delayMs: Int? = null,
        early: Long = 0,
        paced: Boolean = false,
    ): Triple<Int, Int, Int> {
        val scene = ImageComposeScene(100, 100, density = Density(1f)) {
            NxThemed(Themes.default, dark = true, reduceMotion = reduceMotion) {
                Box(Modifier.fillMaxSize().background(ground)) {
                    PlayedWidgetEntrance(entrance, onNode, order, delayMs) {
                        Box(Modifier.size(100.dp).background(block))
                    }
                }
            }
        }
        try {
            val first = Bitmap.makeFromImage(scene.render(0)).getColor(50, 50)
            var t = 0L
            while (t < early * 1_000_000) { t += FRAME_NANOS; scene.render(t).close(); if (paced) Thread.sleep(8) }
            val atEarly = Bitmap.makeFromImage(scene.render(t)).getColor(50, 50)
            repeat(120) { t += FRAME_NANOS; scene.render(t).close(); Thread.sleep(8) }
            val last = Bitmap.makeFromImage(scene.render(t)).getColor(50, 50)
            return Triple(first, atEarly, last)
        } finally {
            scene.close()
        }
    }

    @Test
    fun `an arriving widget is invisible on its first frame and whole at the end`() {
        for (entrance in listOf(Entrance.Fade, Entrance.Rise, Entrance.Settle)) {
            val (first, _, last) = frames(entrance, order = 2)
            assertNotEquals(block.toArgb(), first, "$entrance showed before arriving")
            assertEquals(block.toArgb(), last, "$entrance never finished arriving")
        }
    }

    @Test
    fun `a widget told not to arrive is there from the first frame`() {
        val (first, _, _) = frames(Entrance.None, order = 3)
        assertEquals(block.toArgb(), first)
    }

    @Test
    fun `with reduced motion an arrival the widget declares is held still, its place in the slot included`() {
        for (entrance in Entrance.entries) {
            val (first, _, _) = frames(entrance, order = 2, reduceMotion = true)
            assertEquals(block.toArgb(), first, "$entrance arrived although the look holds it still")
        }
        val (pinned, _, _) = frames(Entrance.Rise, order = 2, reduceMotion = true, delayMs = 300)
        assertEquals(block.toArgb(), pinned, "a delay the node pins times an arrival, and the declared one does not play")
    }

    @Test
    fun `with reduced motion a character and a delay the node names play as set`() {
        for (entrance in listOf(Entrance.Fade, Entrance.Rise, Entrance.Settle)) {
            val (first, waiting, last) = frames(entrance, order = 2, reduceMotion = true, onNode = true, delayMs = 400, early = 50)
            assertNotEquals(block.toArgb(), first, "$entrance named on the node showed before arriving")
            assertEquals(ground.toArgb(), waiting, "$entrance did not wait out the node's own delay")
            assertEquals(block.toArgb(), last, "$entrance named on the node never finished arriving")
        }
        val (first, _, last) = frames(Entrance.None, order = 2, reduceMotion = true, onNode = true)
        assertEquals(block.toArgb(), first, "a node that names no arrival is there at once")
        assertEquals(block.toArgb(), last)
    }

    @Test
    fun `with reduced motion a character the node names starts at once, the slot's stagger held still`() {
        // Thirty paced frames are about a quarter of a second: the 200 ms fade is done by then, and
        // the 420 ms the sixth place waits with motion on would not have begun.
        val (first, settled, _) = frames(Entrance.Fade, order = 6, reduceMotion = true, onNode = true, early = 500, paced = true)
        assertNotEquals(block.toArgb(), first, "a character the node names plays")
        assertEquals(block.toArgb(), settled, "the sixth place waited for a stagger the look holds still")
    }
}
