package hivens.ui.nx

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import hivens.ui.theme.NxTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A selected label must not be wider than the same label unselected.
 *
 * Bolding on selection is how the app says which tab or chip is active, and a
 * bolder face is a wider one -- so the pill grew as it was pressed and its
 * neighbours slid over. The measurement is the point here, not the picture: the
 * shift is a couple of device pixels, which is exactly the size of thing that
 * survives a review of a screenshot and annoys every day.
 */
class NxSteadyTextWidthTest {

    /** Width in pixels of whatever [body] draws, measured in a real layout pass. */
    @OptIn(ExperimentalComposeUiApi::class)
    private fun widthOf(body: @Composable (Modifier) -> Unit): Int {
        var width = -1
        val scene = ImageComposeScene(400, 120, density = Density(2f)) {
            NxTheme(useDarkTheme = true) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                    body(Modifier.onGloballyPositioned { width = it.size.width })
                }
            }
        }
        scene.render()
        scene.close()
        assertTrue(width > 0, "nothing was measured")
        return width
    }

    // The label is deliberately long enough that a per-glyph difference adds up:
    // a two-letter chip can bold without changing its rounded-up width, and would
    // pass this test while every real label in the app still moved.
    private val label = "Resource packs"

    @Test
    fun `a plain label is wider bold than it is regular`() {
        val regular = widthOf { m -> Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Normal, modifier = m) }
        val bold = widthOf { m -> Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, modifier = m) }
        assertTrue(bold > regular, "the premise of the fix: bold ($bold) must measure wider than regular ($regular)")
    }

    @Test
    fun `a steady label measures the same in either weight`() {
        val regular = widthOf { m ->
            NxSteadyText(label, weight = FontWeight.Normal, style = MaterialTheme.typography.labelLarge, modifier = m)
        }
        val bold = widthOf { m ->
            NxSteadyText(label, weight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge, modifier = m)
        }
        assertEquals(bold, regular, "selecting a label must not resize what holds it")
    }

    @Test
    fun `a steady label reserves the heaviest face, not more`() {
        val bold = widthOf { m ->
            Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, modifier = m)
        }
        val steady = widthOf { m ->
            NxSteadyText(label, weight = FontWeight.Normal, style = MaterialTheme.typography.labelLarge, modifier = m)
        }
        assertEquals(bold, steady, "the reserved box is the bold label's own width")
    }
}
