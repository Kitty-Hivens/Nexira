package hivens.ui.nx

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import hivens.ui.icons.NxIcon
import hivens.ui.theme.NxTheme
import hivens.ui.theme.Spacing
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

/**
 * Every state of the cycle control, on both palettes.
 *
 * A control whose whole job is to say which of three modes is on has to be
 * legible in each of them from a still frame: the off state muted, the two
 * active ones accented and filled, and the disabled one visible rather than
 * gone. That is four frames, and none of them is the one a call site happens to
 * draw first.
 */
class NxCycleToggleRenderTest {

    private val modes = listOf(
        NxCycleState(NxIcon.Repeat, "Repeat off"),
        NxCycleState(NxIcon.Repeat, "Repeat queue"),
        NxCycleState(NxIcon.RepeatOne, "Repeat one"),
    )

    @Composable
    private fun Strip(label: String) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s6)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = NxTheme.colors.textSecondary)
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.s10),
                verticalAlignment     = Alignment.CenterVertically,
            ) {
                modes.indices.forEach { i ->
                    NxCycleToggle(states = modes, index = i, onCycle = {})
                }
                NxCycleToggle(states = modes, index = 1, onCycle = {}, enabled = false)
            }
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun sheet(name: String, dark: Boolean) {
        val d = 3f
        val scene = ImageComposeScene((260 * d).toInt(), (86 * d).toInt(), density = Density(d)) {
            NxTheme(useDarkTheme = dark) {
                Box(
                    Modifier.fillMaxSize().background(NxTheme.colors.background).padding(Spacing.s12),
                    contentAlignment = Alignment.CenterStart,
                ) { Strip(if (dark) "off / queue / one / disabled" else "off / queue / one / disabled") }
            }
        }
        val img = scene.render()
        scene.close()
        File("build/render").mkdirs()
        img.encodeToData(EncodedImageFormat.PNG)?.bytes?.let {
            File("build/render/cycle-$name.png").writeBytes(it)
        }
    }

    @Test
    fun probe() {
        sheet("dark", dark = true)
        sheet("light", dark = false)
    }
}
