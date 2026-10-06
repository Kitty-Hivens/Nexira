package hivens.ui.nx

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hivens.ui.theme.NxTheme
import hivens.ui.theme.Themes
import org.jetbrains.skia.Bitmap
import kotlin.test.Test
import kotlin.test.assertNotEquals

/**
 * The hover overlay of a clickable row is drawn in the draw phase, off state the
 * composition never reads. This is the check that it is still drawn at all.
 */
class NxRowHoverRenderTest {

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `hovering a clickable row lightens it`() {
        val scene = ImageComposeScene(width = 400, height = 120, density = Density(1f)) {
            NxTheme(Themes.Celestia, dark = true) {
                Column(Modifier.fillMaxSize().background(Color.Black).padding(horizontal = 24.dp)) {
                    NxRow(title = "Row", onClick = {})
                }
            }
        }
        try {
            var t = 0L
            val before = Bitmap.makeFromImage(scene.render(t)).getColor(PROBE_X, PROBE_Y)
            scene.sendPointerEvent(PointerEventType.Enter, Offset(PROBE_X.toFloat(), PROBE_Y.toFloat()))
            scene.sendPointerEvent(PointerEventType.Move, Offset(PROBE_X.toFloat(), PROBE_Y.toFloat()))
            repeat(40) {
                t += FRAME_NANOS
                scene.render(t)
            }
            val after = Bitmap.makeFromImage(scene.render(t + FRAME_NANOS)).getColor(PROBE_X, PROBE_Y)
            assertNotEquals(before, after, "the row looked the same hovered as not")
        } finally {
            scene.close()
        }
    }

    private companion object {
        const val FRAME_NANOS = 16_000_000L
        // Inside the row, right of the title and left of the empty trailing slot.
        const val PROBE_X = 300
        const val PROBE_Y = 12
    }
}
