package hivens.ui.nx

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hivens.ui.icons.NxIcon
import hivens.ui.theme.BrutStyle
import hivens.ui.theme.CelestiaStyle
import hivens.ui.theme.DarkColorPalette
import hivens.ui.theme.LightColorPalette
import hivens.ui.theme.LocalNxColors
import hivens.ui.theme.LocalStyle
import hivens.ui.theme.NxColors
import hivens.ui.theme.StyleSpec
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Static render sheet of the Play pill's moments (play, busy wait, exit-the-
 * game, disabled ghost, compact, icon-only) over a dark art-like ground -- the
 * scrimmed hero the pill actually lives on. Both styles (capsule vs hard edge)
 * and both palettes (the static ink flips black/white with the theme). Smoke +
 * a PNG under build/render for a manual look; hover/press are animated and
 * verified live.
 */
class PlayButtonRenderTest {

    @OptIn(ExperimentalComposeUiApi::class)
    private fun render(style: StyleSpec, palette: NxColors, name: String) {
        val scene = ImageComposeScene(width = 1150, height = 360, density = Density(2f)) {
            CompositionLocalProvider(
                LocalNxColors provides palette,
                LocalStyle provides style,
            ) {
                Column(
                    modifier            = Modifier.fillMaxSize().background(Color(0xFF16181D)).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        PlayButton(label = "Играть", onClick = {})
                        PlayButton(label = "Подождите", onClick = {}, busy = true)
                        PlayButton(label = "Выход", onClick = {}, icon = NxIcon.Stop)
                        PlayButton(label = "Играть", onClick = {}, enabled = false)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        PlayButton(label = "Играть", onClick = {}, compact = true)
                        PlayButton(label = "Играть", onClick = {}, iconOnly = true)
                        PlayButton(label = "Играть", onClick = {}, iconOnly = true, compact = true)
                        PlayButton(label = "Выход", onClick = {}, icon = NxIcon.Stop, compact = true)
                    }
                }
            }
        }
        try {
            val png = scene.render().encodeToData(EncodedImageFormat.PNG) ?: error("PNG encode failed")
            val out = File("build/render/play-button-$name.png")
            out.parentFile.mkdirs()
            out.writeBytes(png.bytes)
            assertTrue(out.length() > 0)
        } finally {
            scene.close()
        }
    }

    @Test fun `renders under Celestia dark`() = render(CelestiaStyle, DarkColorPalette, "celestia-dark")

    @Test fun `renders under Celestia light`() = render(CelestiaStyle, LightColorPalette, "celestia-light")

    @Test fun `renders under Brut dark`() = render(BrutStyle, DarkColorPalette, "brut-dark")
}
