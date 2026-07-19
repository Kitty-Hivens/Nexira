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
import hivens.ui.theme.BrutStyle
import hivens.ui.theme.CelestiaStyle
import hivens.ui.theme.DarkColorPalette
import hivens.ui.theme.LocalNxColors
import hivens.ui.theme.LocalStyle
import hivens.ui.theme.StyleSpec
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Static render sheet of the Play plate's variants (hero, compact, icon-only,
 * disabled ghost) under both styles, over a dark art-like ground -- the
 * contexts the button actually lives on. Smoke + a PNG under build/render for
 * a manual look; interaction states (hover lift, press sink) are animated and
 * verified live.
 */
class PlayButtonRenderTest {

    @OptIn(ExperimentalComposeUiApi::class)
    private fun render(style: StyleSpec, name: String) {
        val scene = ImageComposeScene(width = 760, height = 400, density = Density(2f)) {
            CompositionLocalProvider(
                LocalNxColors provides DarkColorPalette,
                LocalStyle provides style,
            ) {
                Column(
                    modifier            = Modifier.fillMaxSize().background(Color(0xFF16181D)).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        PlayButton(label = "Играть", onClick = {})
                        PlayButton(label = "Играть", onClick = {}, compact = true)
                        PlayButton(label = "Играть", onClick = {}, iconOnly = true)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        PlayButton(label = "Играть", onClick = {}, enabled = false)
                        PlayButton(label = "Играть", onClick = {}, enabled = false, iconOnly = true, compact = true)
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

    @Test fun `renders under Celestia`() = render(CelestiaStyle, "celestia")

    @Test fun `renders under Brut`() = render(BrutStyle, "brut")
}
