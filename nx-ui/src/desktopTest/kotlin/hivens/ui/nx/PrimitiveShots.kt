package hivens.ui.nx

import hivens.ui.theme.LightColorPalette
import hivens.ui.theme.DarkColorPalette
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hivens.ui.icons.NxIcon
import hivens.ui.surface.NxSurface
import hivens.ui.surface.NxSurfaceLevel
import hivens.ui.theme.BrutStyle
import hivens.ui.theme.CelestiaStyle
import hivens.ui.theme.LocalStyle
import hivens.ui.theme.NxTheme
import hivens.ui.theme.StyleSpec
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Ignore
import kotlin.test.Test

/**
 * Off-screen visual capture of the library's primitives in every style and palette
 * -- the headless stand-in for "look at it". Buttons in each style and state, the
 * switch, the two progress shapes, the four surface levels side by side and one
 * nested, saved as PNGs. @Ignore: run on demand.
 *   ./gradlew :nx-ui:desktopTest --tests "hivens.ui.nx.PrimitiveShots"
 *
 * No assertions on purpose: what this catches is what only an eye catches -- a
 * control that vanishes on one palette, a corner that ignores the style axis, two
 * depths that read as one tone. The measurable parts of that live in NxSurfaceTest.
 */
@Ignore("visual capture harness; run on demand")
class PrimitiveShots {

    private val outDir = File(System.getenv("PRIMITIVE_SHOTS_DIR") ?: "build/render").apply { mkdirs() }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun sheet(name: String, style: StyleSpec, dark: Boolean, ground: Color) {
        val scene = ImageComposeScene(width = 1180, height = 760, density = Density(2f)) {
            NxTheme(useDarkTheme = dark, style = style) {
                CompositionLocalProvider(LocalStyle provides style) {
                    Column(
                        modifier = Modifier.fillMaxSize().background(ground).padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NxButton(label = "Primary", onClick = {}, style = NxButtonStyle.Primary)
                            NxButton(label = "Secondary", onClick = {}, style = NxButtonStyle.Secondary)
                            NxButton(label = "Tertiary", onClick = {}, style = NxButtonStyle.Tertiary)
                            NxButton(label = "Destructive", onClick = {}, style = NxButtonStyle.Destructive)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            NxButton(label = "Disabled", onClick = {}, style = NxButtonStyle.Primary, enabled = false)
                            NxButton(label = "Disabled", onClick = {}, style = NxButtonStyle.Secondary, enabled = false)
                            NxButton(label = "Compact", onClick = {}, style = NxButtonStyle.Secondary, icon = NxIcon.Add, compact = true)
                            NxIconButton(NxIcon.Settings, "settings", onClick = {})
                            NxIconButton(NxIcon.Delete, "delete", onClick = {}, enabled = false)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            NxSwitch(checked = true, onCheckedChange = {})
                            NxSwitch(checked = false, onCheckedChange = {})
                            NxSwitch(checked = true, onCheckedChange = {}, enabled = false)
                            NxProgressBar(progress = 0.45f, modifier = Modifier.width(180.dp))
                            NxProgressBar(progress = null, modifier = Modifier.width(180.dp))
                        }
                        // The tonal ladder: four levels side by side, each carrying text.
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (level in NxSurfaceLevel.entries) {
                                NxSurface(level, Modifier.width(170.dp)) {
                                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(level.name, color = NxTheme.colors.textPrimary)
                                        Text("secondary text", color = NxTheme.colors.textSecondary)
                                    }
                                }
                            }
                        }
                        // Nested: a plane inside a plane has to stay distinguishable.
                        NxSurface(NxSurfaceLevel.Base, Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Base surface", color = NxTheme.colors.textPrimary)
                                NxSurface(NxSurfaceLevel.Raised, Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(10.dp)) {
                                        Text("Raised inside Base", color = NxTheme.colors.textPrimary)
                                        Text("with secondary text under it", color = NxTheme.colors.textSecondary)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        scene.render(0L)
        val image = scene.render(400_000_000L)
        scene.close()

        image.encodeToData(EncodedImageFormat.PNG)?.bytes
            ?.let { File(outDir, "primitives-$name.png").writeBytes(it) }
    }

    @Test
    fun `every primitive composes in every style and palette`() {
        sheet("celestia-dark", CelestiaStyle, dark = true, ground = DarkColorPalette.background)
        sheet("celestia-light", CelestiaStyle, dark = false, ground = LightColorPalette.background)
        sheet("brut-dark", BrutStyle, dark = true, ground = DarkColorPalette.background)
        sheet("brut-light", BrutStyle, dark = false, ground = LightColorPalette.background)
    }
}
