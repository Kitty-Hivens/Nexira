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
import hivens.ui.surface.NxSurface
import hivens.ui.surface.NxSurfaceLevel
import hivens.ui.text.needsCjkFace
import hivens.ui.theme.NxTheme
import hivens.ui.theme.Spacing
import hivens.ui.theme.nexiraCjkFamily
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test

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
        NxSurface(NxSurfaceLevel.Floating, Modifier.width(360.dp()), shape = MaterialTheme.shapes.medium) {
            Column(Modifier.fillMaxWidth().padding(Spacing.s16)) {
                Text(
                    title, style = MaterialTheme.typography.titleMedium,
                    color = NxTheme.colors.textPrimary, fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(Spacing.s12))
                rows.forEach { (label, sub) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = Spacing.s6)) {
                        Column(Modifier.width(320.dp())) {
                            Text(label, style = MaterialTheme.typography.bodyMedium, color = NxTheme.colors.textPrimary)
                            Text(sub, style = MaterialTheme.typography.labelSmall, color = NxTheme.colors.textSecondary)
                        }
                    }
                }
            }
        }
    }

    private fun Int.dp() = androidx.compose.ui.unit.Dp(this.toFloat())

    @OptIn(ExperimentalComposeUiApi::class)
    private fun sheet(name: String, locale: AppLocale) {
        val d = 2f
        val scene = ImageComposeScene((760 * d).toInt(), (420 * d).toInt(), density = Density(d)) {
            LocaleProvider(locale) {
                val s = LocalStrings.current
                // The same decision AppShell makes, not a copy of its outcome.
                val family = if (!needsCjkFace(s.settingsTitle + s.navLibrary + s.aboutTitle)) {
                    null
                } else {
                    nexiraCjkFamily()
                }
                NxTheme(useDarkTheme = true, uiFamily = family) {
                    Box(
                        Modifier.fillMaxSize().background(NxTheme.colors.background).padding(Spacing.s16),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s16)) {
                            Panel(
                                s.settingsTitle,
                                listOf(
                                    s.settingsLanguage to locale.displayName,
                                    s.settingsDarkTheme to s.settingsDarkThemeDesc,
                                    s.settingsSurfaceBlur to s.settingsSurfaceBlurDesc,
                                    s.settingsCloseAfterLaunch to s.settingsCloseAfterLaunchDesc,
                                ),
                            )
                            Panel(
                                s.packDetailTabSettings,
                                listOf(
                                    s.packSettingsRepair to s.packSettingsRepairDesc,
                                    s.packVersionSnapshots to s.packVersionSnapshotsHint,
                                    s.audioPickTrack to s.audioFormatHint,
                                    s.musicPlayerTitle to "追憶のサクラメント / めらみぽっぷ",
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
        sheet("ja", AppLocale.JAPANESE)
        sheet("en", AppLocale.ENGLISH)
    }
}
