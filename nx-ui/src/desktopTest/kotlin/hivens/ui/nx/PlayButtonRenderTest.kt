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
import hivens.ui.theme.DarkColorPalette
import hivens.ui.theme.LightColorPalette
import hivens.ui.theme.LocalNxColors
import hivens.ui.theme.NxColors
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
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
    private fun render(palette: NxColors, name: String) {
        val scene = ImageComposeScene(width = 1150, height = 360, density = Density(2f)) {
            CompositionLocalProvider(
                LocalNxColors provides palette,
            ) {
                Column(
                    modifier            = Modifier.fillMaxSize().background(Color(BACKDROP)).padding(16.dp),
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
        val painted: Double
        try {
            val frame = scene.render()
            val png = frame.encodeToData(EncodedImageFormat.PNG) ?: error("PNG encode failed")
            val out = File("build/render/play-button-$name.png")
            out.parentFile.mkdirs()
            out.writeBytes(png.bytes)
            painted = paintedFraction(frame)
        } finally {
            scene.close()
        }
        // A composition that throws still encodes a valid PNG of the bare backdrop,
        // so file size says nothing about whether the buttons are on it.
        assertTrue(painted > MIN_PAINTED, "the sheet covers ${(painted * 100).toInt()}% of the frame -- it did not render")
    }

    @Test fun `renders on the dark palette`() = render(DarkColorPalette, "dark")

    @Test fun `renders on the light palette`() = render(LightColorPalette, "light")


    /** Share of sampled pixels that are not the bare backdrop the sheet sits on. */
    private fun paintedFraction(frame: Image): Double {
        val bmp = Bitmap.makeFromImage(frame)
        var painted = 0
        var sampled = 0
        var y = 0
        while (y < bmp.height) {
            var x = 0
            while (x < bmp.width) {
                if (bmp.getColor(x, y) != BACKDROP) painted++
                sampled++
                x += 4
            }
            y += 4
        }
        return painted.toDouble() / sampled
    }

    private companion object {
        /** What the sheet is cleared to, so anything else on it is a button. */
        val BACKDROP = 0xFF16181D.toInt()

        /**
         * Eight buttons on the sheet cover far more than this; an empty frame
         * covers nothing. Only has to tell those two apart.
         */
        const val MIN_PAINTED = 0.05
    }
}
