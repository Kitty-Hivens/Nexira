package hivens.ui.nx

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable as androidxHoverable
import androidx.compose.foundation.interaction.collectIsHoveredAsState as collectIsHovered
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.Density
import hivens.ui.theme.NxTheme
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Does hover actually raise the tooltip.
 *
 * The rewrite swapped `TooltipArea` for a hand-rolled hover plus [androidx.compose.ui.window.Popup],
 * and a call site that used to work stopped. That is not a thing to settle by
 * pointing at the running app: `ImageComposeScene.sendPointerEvent` drives the
 * pointer off-screen, so the question becomes a pixel count.
 */
class NxTooltipHoverTest {

    private val anchorColor = Color(0xFF3355FF)
    private val tipColor = Color(0xFFFF2200)

    /** Pixels of [tipColor] in the frame. The tooltip draws in a colour nothing else uses. */
    private fun tipInk(bmp: Bitmap): Int {
        var hits = 0
        val want = tipColor.toArgb()
        for (y in 0 until bmp.height) {
            for (x in 0 until bmp.width) {
                val c = bmp.getColor(x, y)
                if (near(c, want)) hits++
            }
        }
        return hits
    }

    private fun Color.toArgb(): Int =
        (0xFF shl 24) or ((red * 255).toInt() shl 16) or ((green * 255).toInt() shl 8) or (blue * 255).toInt()

    private fun near(a: Int, b: Int): Boolean {
        fun ch(v: Int, s: Int) = (v shr s) and 0xFF
        return kotlin.math.abs(ch(a, 16) - ch(b, 16)) < 12 &&
            kotlin.math.abs(ch(a, 8) - ch(b, 8)) < 12 &&
            kotlin.math.abs(ch(a, 0) - ch(b, 0)) < 12
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun hoverInk(behaviour: NxTooltipBehaviour): Pair<Int, Int> {
        val scene = ImageComposeScene(width = 400, height = 300, density = Density(1f)) {
            NxTheme(useDarkTheme = true) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    NxTooltip(
                        tooltip = { Box(Modifier.size(40.dp2()).background(tipColor)) },
                        behaviour = behaviour,
                    ) {
                        Box(Modifier.size(60.dp2()).background(anchorColor))
                    }
                }
            }
        }
        var t = 0L
        val before = tipInk(Bitmap.makeFromImage(scene.render(t)))
        scene.sendPointerEvent(PointerEventType.Enter, Offset(30f, 30f))
        scene.sendPointerEvent(PointerEventType.Move, Offset(30f, 30f))
        // The frame clock is advanced by hand. `render()` with no argument repeats the
        // same instant, so an enter transition never leaves its initial alpha and an
        // appearing popup reads as a popup that never appeared.
        var after = 0
        repeat(20) {
            Thread.sleep(16)
            t += 16_000_000L
            after = maxOf(after, tipInk(Bitmap.makeFromImage(scene.render(t))))
        }
        scene.close()
        return before to after
    }

    private fun Int.dp2() = androidx.compose.ui.unit.Dp(this.toFloat())

    /**
     * The control. If the harness cannot see Compose's own tooltip either, then a
     * blank frame says nothing about my implementation and the experiment is void.
     */
    @OptIn(ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
    @Test
    fun `control, the stock TooltipArea under the same pointer sequence`() {
        val scene = ImageComposeScene(width = 400, height = 300, density = Density(1f)) {
            NxTheme(useDarkTheme = true) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    TooltipArea(
                        tooltip = { Box(Modifier.size(40.dp2()).background(tipColor)) },
                        delayMillis = 0,
                    ) {
                        Box(Modifier.size(60.dp2()).background(anchorColor))
                    }
                }
            }
        }
        scene.render()
        scene.sendPointerEvent(PointerEventType.Enter, Offset(30f, 30f))
        scene.sendPointerEvent(PointerEventType.Move, Offset(30f, 30f))
        var seen = 0
        repeat(8) {
            Thread.sleep(30)
            seen = maxOf(seen, tipInk(Bitmap.makeFromImage(scene.render())))
        }
        scene.close()
        println("CONTROL TooltipArea inked pixels: $seen")
        assertTrue(seen > 100, "the harness cannot see even the stock tooltip, so it proves nothing: $seen")
    }

    /**
     * Splits the space: is it my tooltip that fails, or is `Modifier.hoverable`
     * itself deaf to a synthesised pointer. `TooltipArea` listens with
     * `onPointerEvent`, not with hoverable, so the control above does not answer this.
     */
    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `does Modifier hoverable react to a synthesised pointer at all`() {
        val scene = ImageComposeScene(width = 400, height = 300, density = Density(1f)) {
            val src = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            val hovered by src.collectIsHovered()
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                Box(
                    Modifier
                        .size(60.dp2())
                        .androidxHoverable(src)
                        .background(if (hovered) tipColor else anchorColor),
                )
            }
        }
        scene.render()
        scene.sendPointerEvent(PointerEventType.Enter, Offset(30f, 30f))
        scene.sendPointerEvent(PointerEventType.Move, Offset(30f, 30f))
        var seen = 0
        repeat(8) {
            Thread.sleep(30)
            seen = maxOf(seen, tipInk(Bitmap.makeFromImage(scene.render())))
        }
        scene.close()
        println("HOVERABLE inked pixels: $seen")
        assertTrue(seen > 100, "Modifier.hoverable did not react to the synthesised pointer: $seen")
    }

    /**
     * Replicates the component's exact wrapper: hoverable FIRST, with no size
     * modifier ahead of it, then the pointerInput loop, on a Box that takes its
     * size from its child. Splits "my chain kills hover" from "hover is fine and
     * the popup half is broken".
     */
    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `the component's own modifier chain still reports hover`() {
        val scene = ImageComposeScene(width = 400, height = 300, density = Density(1f)) {
            val src = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            val hovered by src.collectIsHovered()
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                Box(
                    Modifier
                        .androidxHoverable(src)
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val e = awaitPointerEvent()
                                    if (e.type == PointerEventType.Move || e.type == PointerEventType.Enter) Unit
                                }
                            }
                        },
                ) {
                    Box(Modifier.size(60.dp2()).background(if (hovered) tipColor else anchorColor))
                }
            }
        }
        scene.render()
        scene.sendPointerEvent(PointerEventType.Enter, Offset(30f, 30f))
        scene.sendPointerEvent(PointerEventType.Move, Offset(30f, 30f))
        var seen = 0
        repeat(8) {
            Thread.sleep(30)
            seen = maxOf(seen, tipInk(Bitmap.makeFromImage(scene.render())))
        }
        scene.close()
        println("CHAIN inked pixels: $seen")
        assertTrue(seen > 100, "the component's own modifier chain reports no hover: $seen")
    }

    /**
     * The last control: [NxContextMenu] uses the identical popup pattern (a
     * MutableTransitionState, a conditional Popup, AnimatedVisibility, NxSurface)
     * and is known to work in the app. If it inks here and my tooltip does not,
     * the difference between the two is the bug.
     */
    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `control, the context menu popup pattern under the same harness`() {
        val scene = ImageComposeScene(width = 400, height = 300, density = Density(1f)) {
            NxTheme(useDarkTheme = true) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    Box {
                        Box(Modifier.size(60.dp2()).background(anchorColor))
                        NxContextMenu(expanded = true, onDismissRequest = {}) {
                            Box(Modifier.size(40.dp2()).background(tipColor))
                        }
                    }
                }
            }
        }
        var seen = 0
        repeat(8) {
            Thread.sleep(30)
            seen = maxOf(seen, tipInk(Bitmap.makeFromImage(scene.render())))
        }
        scene.close()
        println("MENU-PATTERN inked pixels: $seen")
        assertTrue(seen > 100, "the shared popup pattern does not render here either: $seen")
    }

    @Test
    fun `hover raises the tooltip when there is no delay`() {
        val (before, after) = hoverInk(NxTooltipBehaviour.Label.copy(delayMillis = 0))
        assertTrue(before == 0, "nothing may be drawn before the pointer arrives, saw $before")
        assertTrue(after > 100, "hover must raise the tooltip, saw $after inked pixels")
    }

    @Test
    fun `an anchored tooltip raises the same way`() {
        val (before, after) = hoverInk(
            NxTooltipBehaviour.Explain.copy(delayMillis = 0),
        )
        assertTrue(before == 0, "nothing before the pointer, saw $before")
        assertTrue(after > 100, "an anchored tooltip must raise on hover, saw $after")
    }
}
