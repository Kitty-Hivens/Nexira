package hivens.ui.nx

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hivens.core.data.PackOrigin
import hivens.ui.components.SourceBadge
import hivens.ui.i18n.LocalStrings
import hivens.ui.i18n.RussianStrings
import hivens.ui.theme.NxTheme
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

/**
 * The Library card's label row, over the banner brightnesses a card actually lands on.
 *
 * A probe rather than an assertion: it draws the thing and leaves a PNG to look at.
 * The card's own scrim is reproduced here because it is what the chips are really
 * drawn over -- judging the tones against raw art would answer a question the
 * card never asks.
 */
class MetaChipContrastProbe {

    /** The card's three-layer background, without the parts that need a network. */
    @Composable
    private fun CardRow(art: Color, scrim: Float, content: @Composable () -> Unit) {
        Box(Modifier.fillMaxWidth().height(84.dp).background(art)) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = scrim)))
            Box(Modifier.fillMaxSize().padding(14.dp), contentAlignment = Alignment.CenterStart) { content() }
        }
    }

    @Test
    fun `the label row over banners of every brightness`() {
        val out = Path.of("build/render", "meta-chip-contrast.png")
        Files.createDirectories(out.parent)
        val banners = listOf(
            "white" to Color(0xFFFFFFFF),
            "bright pixel art" to Color(0xFFE8D8A0),
            "sky" to Color(0xFF7FB7E8),
            "grass" to Color(0xFF5A8F3C),
            "stone" to Color(0xFF7A7A7A),
            "night" to Color(0xFF14161C),
        )
        val scene = ImageComposeScene(980, 60 + banners.size * 2 * 96, density = Density(1f)) {
            NxTheme(useDarkTheme = true) {
                CompositionLocalProvider(LocalStrings provides RussianStrings) {
                    Box(Modifier.fillMaxSize().background(NxTheme.colors.background).padding(16.dp)) {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            banners.forEach { (name, fill) ->
                                // 0.45 is the scrim over a real banner, 0.32 the one over
                                // the generated pixel art an art-less pack falls back to.
                                listOf(0.45f, 0.32f).forEach { scrim ->
                                    CardRow(fill, scrim) {
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            verticalAlignment     = Alignment.CenterVertically,
                                        ) {
                                            Text(
                                                "$name ${(scrim * 100).toInt()}%",
                                                style = MaterialTheme.typography.titleMedium,
                                                color = Color.White,
                                                fontWeight = FontWeight.SemiBold,
                                            )
                                            SourceBadge(PackOrigin.Mirror)
                                            NxMetaChip("1.21.1-0.4.2", tone = NxMetaChipTone.OnMedia)
                                            NxMetaChip("fork", tone = NxMetaChipTone.OnMediaAccent)
                                            NxMetaChip("вчера", tone = NxMetaChipTone.OnMedia)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        try {
            val png = scene.render().encodeToData(EncodedImageFormat.PNG) ?: error("PNG encode failed")
            Files.write(out, png.bytes)
        } finally {
            scene.close()
        }
        println("probe written: ${out.toAbsolutePath()}")
    }
}
