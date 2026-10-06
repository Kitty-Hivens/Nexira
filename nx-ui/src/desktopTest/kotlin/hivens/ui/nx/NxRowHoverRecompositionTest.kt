package hivens.ui.nx

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import hivens.ui.theme.NxTheme
import hivens.ui.theme.Themes
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Hovering a row fades one rectangle in. It must not recompose the row's contents on
 * every frame of that fade, which is what reading the animated value in composition
 * did. [NxNavRow] draws the same overlay through the same two functions and has no
 * slot a probe could sit in, so the in-plane row stands for both.
 */
class NxRowHoverRecompositionTest {

    @OptIn(ExperimentalComposeUiApi::class)
    private fun compositionsDuringHover(row: @Composable (probe: @Composable () -> Unit) -> Unit): Int {
        var compositions = 0
        val scene = ImageComposeScene(width = 400, height = 200, density = Density(1f)) {
            NxTheme(Themes.Celestia, dark = true) {
                Column(Modifier.fillMaxSize()) {
                    row { SideEffect { compositions++ } }
                }
            }
        }
        try {
            var t = 0L
            scene.render(t)
            val before = compositions
            scene.sendPointerEvent(PointerEventType.Enter, Offset(100f, 20f))
            scene.sendPointerEvent(PointerEventType.Move, Offset(100f, 20f))
            repeat(40) {
                t += FRAME_NANOS
                scene.render(t)
            }
            return compositions - before
        } finally {
            scene.close()
        }
    }

    @Test
    fun `hovering a row does not recompose it per frame`() {
        val n = compositionsDuringHover { probe -> NxRow(title = "Row", onClick = {}, trailing = probe) }
        assertTrue(n <= MAX_COMPOSITIONS, "a row recomposed $n times while its hover faded in")
    }

    private companion object {
        const val FRAME_NANOS = 16_000_000L
        /** The hover edge itself recomposes once; a frame-by-frame fade is dozens. */
        const val MAX_COMPOSITIONS = 3
    }
}
