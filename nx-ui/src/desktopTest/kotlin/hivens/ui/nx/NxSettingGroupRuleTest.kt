package hivens.ui.nx

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hivens.ui.theme.NxThemed
import hivens.ui.theme.TestTheme
import org.jetbrains.skia.Bitmap
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

/**
 * A row with nothing to show leaves no trace in a group: the group draws its hairlines from where
 * its rows land and passes over one that measured zero, so two rules never meet where it stands and
 * the group is the same, pixel for pixel, as one that never had it.
 */
class NxSettingGroupRuleTest {

    @Test
    fun `an empty row between two draws one rule, as if it were not there`() {
        val without = render { Row(); Row() }
        val with = render { Row(); Empty(); Row() }
        assertContentEquals(without, with, "the empty row left a second rule or room behind")
    }

    @Test
    fun `an empty row at either end draws no rule of its own`() {
        val without = render { Row(); Row() }
        assertContentEquals(without, render { Empty(); Row(); Row() }, "a rule above the first row")
        assertContentEquals(without, render { Row(); Row(); Empty() }, "a rule below the last row")
    }

    @Test
    fun `a row that draws does add its rule`() {
        val two = render { Row(); Row() }
        val three = render { Row(); Row(); Row() }
        assertFalse(two.contentEquals(three), "the comparison above would pass whatever the group drew")
    }

    @Composable
    private fun Row() = Box(Modifier.fillMaxWidth().height(40.dp))

    /** A row that is there and measures no height: a slot whose widget is left out, say. */
    @Composable
    private fun Empty() = Box(Modifier.fillMaxWidth())

    @OptIn(ExperimentalComposeUiApi::class)
    private fun render(rows: @Composable () -> Unit): ByteArray {
        val scene = ImageComposeScene(width = 300, height = 260, density = Density(1f)) {
            NxThemed(TestTheme, dark = true) {
                Box(Modifier.fillMaxSize().background(Color(0xFF121212)).padding(16.dp)) {
                    NxSettingGroup(title = null, content = rows)
                }
            }
        }
        val image = try {
            scene.render()
        } finally {
            scene.close()
        }
        val bitmap = Bitmap.makeFromImage(image)
        image.close()
        return try {
            assertNotNull(bitmap.readPixels(bitmap.imageInfo, bitmap.imageInfo.minRowBytes, 0, 0))
        } finally {
            bitmap.close()
        }
    }
}
