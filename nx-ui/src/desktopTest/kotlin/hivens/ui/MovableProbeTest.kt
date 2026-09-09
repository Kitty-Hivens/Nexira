package hivens.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.jetbrains.skia.Bitmap
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Can a widget be lifted into the chaos overlay without being rebuilt?
 *
 * The decorator that wraps every widget is handed the content lambda, so moving
 * one into an overlay is a question of where that lambda is invoked, not of
 * reaching inside anything. What is not obvious is whether the subtree keeps its
 * own `remember`ed state across the move: if it does not, a widget that escapes
 * restarts, which for a music player means the track stops.
 *
 * Measured against a control that moves the same content without
 * [movableContentOf], so a passing result cannot be the harness being blind.
 */
class MovableProbeTest {

    private val cornerA = 10 to 10
    private val cornerB = 180 to 180

    @OptIn(ExperimentalComposeUiApi::class)
    private fun moveAndReadColour(movable: Boolean): Pair<Int, Int> {
        var inA by mutableStateOf(true)
        var bump: (() -> Unit)? = null

        val scene = ImageComposeScene(width = 200, height = 200, density = Density(1f)) {
            val body: @Composable () -> Unit = {
                var n by remember { mutableStateOf(0) }
                bump = { n++ }
                Box(Modifier.size(20.dp).background(if (n == 0) Color.Red else Color.Green))
            }
            val content = remember { if (movable) movableContentOf(body) else body }
            // Two separate call sites, not one with a changing argument: the
            // first version of this only moved an Alignment parameter, the
            // subtree never left its group, and the control "passed" by keeping
            // state it had never lost.
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                if (inA) {
                    Box(Modifier.align(Alignment.TopStart)) { content() }
                } else {
                    Box(Modifier.align(Alignment.BottomEnd)) { content() }
                }
            }
        }

        scene.render()
        bump!!()          // the subtree's own state, not the caller's
        scene.render()
        inA = false       // reparent
        val bmp = Bitmap.makeFromImage(scene.render())
        scene.close()

        fun at(p: Pair<Int, Int>) = bmp.getColor(p.first, p.second)
        return at(cornerA) to at(cornerB)
    }

    @Test
    fun `a moved subtree keeps the state it remembered`() {
        val green = Color.Green.toArgb()
        val red = Color.Red.toArgb()
        val black = Color.Black.toArgb()

        val (movedA, movedB) = moveAndReadColour(movable = true)
        val (plainA, plainB) = moveAndReadColour(movable = false)

        println("MOVABLE topLeft=${hex(movedA)} bottomRight=${hex(movedB)}")
        println("PLAIN   topLeft=${hex(plainA)} bottomRight=${hex(plainB)}")

        assertEquals(black, movedA, "the widget should have left the first parent")
        assertEquals(green, movedB, "movableContentOf must carry the remembered state across")
        assertEquals(red, plainB, "the control must lose it, or this test proves nothing")
    }

    private fun hex(c: Int) = "#%08X".format(c)

    private fun Color.toArgb(): Int =
        (0xFF shl 24) or ((red * 255).toInt() shl 16) or ((green * 255).toInt() shl 8) or (blue * 255).toInt()
}

private typealias Composable = androidx.compose.runtime.Composable
