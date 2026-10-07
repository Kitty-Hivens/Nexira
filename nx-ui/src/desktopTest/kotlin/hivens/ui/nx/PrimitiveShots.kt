package hivens.ui.nx

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
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hivens.ui.icons.NxIcon
import hivens.ui.surface.NxSurface
import hivens.ui.theme.NxTheme
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Ignore
import kotlin.test.Test
import hivens.ui.theme.NxInk
import hivens.ui.surface.SurfaceKind
import hivens.ui.theme.Theme
import hivens.ui.theme.Themes
import hivens.ui.theme.NxColor

/**
 * Off-screen visual capture of the library's primitives in every theme and mode,
 * the headless stand-in for "look at it". Buttons in each style and state, the
 * switch, the two progress shapes, the surface vocabulary side by side and one
 * nesting, saved as PNGs. @Ignore: run on demand.
 *   ./gradlew :nx-ui:desktopTest --tests "hivens.ui.nx.PrimitiveShots"
 *
 * No assertions on purpose: what this catches is what only an eye catches -- a
 * control that vanishes in one theme, two depths that read as one tone. The
 * measurable parts of that live in ThemeIntentTest and NxSurfaceTest.
 */
@Ignore("visual capture harness; run on demand")
class PrimitiveShots {

    private val outDir = File(System.getenv("PRIMITIVE_SHOTS_DIR") ?: "build/render").apply { mkdirs() }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun sheet(name: String, theme: Theme, dark: Boolean) {
        val scene = ImageComposeScene(width = 1180, height = 760, density = Density(2f)) {
            NxTheme(theme, dark = dark) {
                run {
                    Column(
                        modifier = Modifier.fillMaxSize().background(NxColor.page).padding(12.dp),
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
                        // The vocabulary side by side, each carrying both inks.
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (kind in listOf(SurfaceKind.Panel, SurfaceKind.Card, SurfaceKind.Field, SurfaceKind.Popup)) {
                                NxSurface(kind, Modifier.width(130.dp), shadowDp = 0f) {
                                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Text(kind.name, color = NxInk.main)
                                        Text("quiet text", color = NxInk.quiet)
                                    }
                                }
                            }
                        }
                        // Nested: each plane is one step above what holds it, and a field steps back.
                        NxSurface(SurfaceKind.Panel, Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Panel", color = NxInk.main)
                                NxSurface(SurfaceKind.Card, Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Text("Card inside the panel", color = NxInk.main)
                                        NxField(value = "", onValueChange = {}, placeholder = "A field inside the card")
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
    fun `every primitive composes in every theme and mode`() {
        for (theme in Themes.all) {
            sheet("${theme.id}-dark", theme, dark = true)
            if (theme.hasLight) sheet("${theme.id}-light", theme, dark = false)
        }
    }
}
