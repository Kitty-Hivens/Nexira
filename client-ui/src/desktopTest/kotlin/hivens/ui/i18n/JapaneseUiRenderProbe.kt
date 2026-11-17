package hivens.ui.i18n

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hivens.themes.Themes
import hivens.ui.i18n.NexiraStrings
import hivens.ui.i18n.ShippedLanguageProvider
import hivens.ui.i18n.nexiraStrings
import hivens.ui.surface.NxSurface
import hivens.ui.text.needsCjkFace
import hivens.ui.theme.NxThemed
import hivens.ui.theme.nexiraCjkFamily
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import hivens.ui.theme.NxInk
import hivens.ui.theme.NxColor
import hivens.ui.surface.SurfaceKind

/**
 * Proof that the whole chain holds: locale, strings, and the face that draws
 * them.
 *
 * A green compile says the Japanese object implements the interface. It says
 * nothing about whether the glyphs come from the bundle or from the host, and on
 * a machine with Noto CJK installed those two look identical. So this renders a
 * panel of real strings with exactly the family AppShell picks, off-screen, where
 * the host's fonts are still reachable but the bundled face is what the type
 * scale was handed.
 */
class JapaneseUiRenderProbe {

    @Composable
    private fun Panel(title: String, rows: List<Pair<String, String>>) {
        NxSurface(SurfaceKind.Panel, Modifier.width(360.dp()), shape = MaterialTheme.shapes.medium) {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Text(
                    title, style = MaterialTheme.typography.titleMedium,
                    color = NxInk.main, fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(12.dp))
                rows.forEach { (label, sub) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                        Column(Modifier.width(320.dp())) {
                            Text(label, style = MaterialTheme.typography.bodyMedium, color = NxInk.main)
                            Text(sub, style = MaterialTheme.typography.labelSmall, color = NxInk.quiet)
                        }
                    }
                }
            }
        }
    }

    private fun Int.dp() = androidx.compose.ui.unit.Dp(this.toFloat())

    @OptIn(ExperimentalComposeUiApi::class)
    private fun sheet(name: String, language: String) {
        val d = 2f
        val scene = ImageComposeScene((760 * d).toInt(), (420 * d).toInt(), density = Density(d)) {
            ShippedLanguageProvider(language) {
                val s = nexiraStrings()
                // The same decision AppShell makes, not a copy of its outcome.
                val family = if (!needsCjkFace(s.text("settingsTitle") + s.text("navLibrary") + s.text("aboutTitle"))) {
                    null
                } else {
                    nexiraCjkFamily()
                }
                NxThemed(Themes.default, dark = true, uiFamily = family) {
                    Box(
                        Modifier.fillMaxSize().background(NxColor.page).padding(16.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            Panel(
                                s.text("settingsTitle"),
                                listOf(
                                    s.text("settingsLanguage") to NexiraStrings.translator(language).language.nativeName,
                                    s.text("settingsDarkTheme") to s.text("settingsDarkThemeDesc"),
                                    s.text("settingsSurfaceBlur") to s.text("settingsSurfaceBlurDesc"),
                                    s.text("settingsCloseAfterLaunch") to s.text("settingsCloseAfterLaunchDesc"),
                                ),
                            )
                            Panel(
                                s.text("packDetailTabSettings"),
                                listOf(
                                    s.text("packSettingsRepair") to s.text("packSettingsRepairDesc"),
                                    s.text("packVersionSnapshots") to s.text("packVersionSnapshotsHint"),
                                    s.text("audioPickTrack") to s.text("audioFormatHint"),
                                    s.text("musicPlayerTitle") to "追憶のサクラメント / めらみぽっぷ",
                                ),
                            )
                        }
                    }
                }
            }
        }
        val img = scene.render()
        scene.close()
        File("build/render").mkdirs()
        img.encodeToData(EncodedImageFormat.PNG)?.bytes?.let {
            File("build/render/locale-$name.png").writeBytes(it)
        }
    }

    @Test
    fun probe() {
        sheet("ja", "ja")
        sheet("en", "en")
    }
}
