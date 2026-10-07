package hivens.ui.nx

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import hivens.ui.theme.NxTheme
import hivens.ui.theme.Themes
import kotlin.test.Test

/** A clickable row measured with no width limit, as a horizontal scroller measures it. */
class NxRowUnboundedTest {

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `a clickable row lays out in a horizontal scroller`() {
        val scene = ImageComposeScene(width = 400, height = 80, density = Density(1f)) {
            NxTheme(Themes.Celestia, dark = true) {
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    NxRow(title = "Row", onClick = {})
                }
            }
        }
        try {
            scene.render()
        } finally {
            scene.close()
        }
    }
}
