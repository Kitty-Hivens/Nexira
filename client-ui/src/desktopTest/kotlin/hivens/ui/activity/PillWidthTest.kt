package hivens.ui.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hivens.ui.i18n.EnglishStrings
import hivens.ui.i18n.LocalStrings
import hivens.ui.theme.CelestiaStyle
import hivens.ui.theme.LocalStyle
import hivens.ui.theme.NxTheme
import org.jetbrains.skia.Bitmap
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The object is as wide as what it holds, up to a ceiling. Nothing else.
 *
 * Written because the same defect was fixed three times from screenshots: a
 * width floor, a weighted spacer and a row told to fill were each stretching it,
 * and removing two of them still left it running the width of the column with
 * its contents crowded into the first third. Every one of those passes an
 * eyeball test on the day and none of them survives this.
 *
 * The measurement is the object's own extent in the frame, so it holds whatever
 * the object is made of.
 */
class PillWidthTest {

    private val frame = 1400
    private val ground = Color(0xFF101014)
    private val cap = 700.dp

    private fun item(n: Int, title: String = "Sodium") =
        SelectionItem("mods:$n", title)

    private fun selectionOf(count: Int, title: String = "Sodium") = Selection(
        items = (1..count).map { item(it, title) },
        actions = listOf(SelectionAction(SelectionActionKind.Delete) {}),
        clear = {},
    )

    internal fun measure(count: Int): Int = widthOf(selectionOf(count))

    @OptIn(ExperimentalComposeUiApi::class)
    private fun widthOf(selection: Selection, maxWidth: Dp = cap): Int {
        val scene = ImageComposeScene(width = frame, height = 140, density = Density(1f)) {
            NxTheme(useDarkTheme = true, style = CelestiaStyle) {
                CompositionLocalProvider(
                    LocalStyle provides CelestiaStyle,
                    LocalStrings provides EnglishStrings,
                ) {
                    Box(Modifier.fillMaxSize().background(ground).padding(20.dp)) {
                        SelectionPill(selection, null, PillProps(), EnglishStrings, maxWidth)
                    }
                }
            }
        }
        val image = scene.render()
        scene.close()
        val bmp = Bitmap.makeFromImage(image)
        val g = ground.toArgb()
        val cols = (0 until frame).filter { x ->
            (0 until 140).any { y -> !near(bmp.getColor(x, y), g) }
        }
        return if (cols.isEmpty()) 0 else cols.last() - cols.first() + 1
    }

    private fun Color.toArgb(): Int =
        (0xFF shl 24) or ((red * 255).toInt() shl 16) or ((green * 255).toInt() shl 8) or (blue * 255).toInt()

    private fun near(a: Int, b: Int, tolerance: Int = 10): Boolean =
        abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) < tolerance &&
            abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) < tolerance &&
            abs((a and 0xFF) - (b and 0xFF)) < tolerance

    @Test
    fun `width follows what the object holds`() {
        val one = widthOf(selectionOf(1))
        val many = widthOf(selectionOf(4))
        assertTrue(many > one, "a fuller stack must make a wider object: $one -> $many")
    }

    @Test
    fun `each face adds the same width, so nothing props the object up from below`() {
        // Self-calibrating on purpose. An assertion of the form "narrower than
        // some fraction of the ceiling" passes happily with a width floor holding
        // the object up -- which is one of the three stretchers this file exists
        // for, and the one the first version of this test let through.
        //
        // A floor cannot be seen in any single width. It shows in the increments:
        // it flattens the small end while the large end grows normally.
        val widths = (1..4).map { widthOf(selectionOf(it)) }
        val steps = widths.zipWithNext { a, b -> b - a }
        val largest = steps.max()
        val smallest = steps.min()
        assertTrue(
            smallest > 0 && largest - smallest <= largest / 3,
            "each face should add about the same width, got widths=$widths steps=$steps",
        )
    }

    @Test
    fun `the ceiling still holds`() {
        val long = widthOf(selectionOf(1, title = "A".repeat(300)))
        assertTrue(long <= 700 + 2, "a long name must be clamped, not overflow: $long")
    }

    @Test
    fun `a narrow allowance is respected over the content`() {
        val narrow = widthOf(selectionOf(4), maxWidth = 320.dp)
        assertTrue(narrow <= 322, "content must yield to the ceiling: $narrow")
    }
}
