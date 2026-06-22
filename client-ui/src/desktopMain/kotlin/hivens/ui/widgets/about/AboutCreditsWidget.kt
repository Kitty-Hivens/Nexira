package hivens.ui.widgets.about

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import hivens.ui.BuildConfig
import hivens.ui.components.GlassCard
import hivens.ui.easter.GibberishMode
import hivens.ui.easter.LocalAprilFools
import hivens.ui.i18n.LocalStrings
import hivens.ui.theme.NxTheme
import hivens.widget.api.rememberProps
import hivens.widget.model.PropLabel
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import kotlinx.serialization.Serializable

// Credits + technology list + license. Self-scrolls because the
// content is long; the surface does not impose a verticalScroll on
// the slot. AprilFools occasionally corrupts these strings (role
// scramble, tech-name zalgo, license-text lorem) -- the left column
// embraces the chaos.
@Serializable
data class AboutCreditsProps(
    // Overrides only the top "Авторы" section header; the Технологии and
    // Лицензия sections stay localized.
    @PropLabel("widget.about.credits.title") val title: String = "",
)

@Widget(id = "about.credits", displayName = "widget.about.credits", propsClass = AboutCreditsProps::class)
@Composable
fun AboutCreditsWidget(instance: WidgetInstance) {
    val p = instance.rememberProps<AboutCreditsProps>()
    val af = LocalAprilFools.current
    val s = LocalStrings.current

    GlassCard(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            SectionLabel(p.title.ifBlank { s.aboutSectionCreator })
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model              = "https://github.com/Kitty-Hivens.png?size=256",
                    contentDescription = "Haru",
                    modifier           = Modifier.size(46.dp).clip(CircleShape),
                    contentScale       = ContentScale.Crop,
                    filterQuality      = FilterQuality.High,
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text       = "Haru (Hivens)",
                        fontWeight = FontWeight.Bold,
                        color      = NxTheme.colors.textPrimary,
                    )
                    Text(
                        text  = af.maybeGibberish(
                            "Architect & Developer",
                            probability = 0.35f,
                            mode        = GibberishMode.SCRAMBLED,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = NxTheme.colors.primary,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            SectionLabel(s.aboutSectionTechnologies)
            Spacer(Modifier.height(8.dp))

            val techs = listOf(
                "Kotlin ${KotlinVersion.CURRENT}"      to s.techKotlinDesc,
                "Compose ${BuildConfig.COMPOSE_VERSION}" to s.techComposeDesc,
                "Ktor ${BuildConfig.KTOR_VERSION}"     to s.techKtorDesc,
                "Koin ${BuildConfig.KOIN_VERSION}"     to s.techKoinDesc,
                "Coil ${BuildConfig.COIL_VERSION}"     to s.techCoilDesc,
                "Skia (Skiko)"                          to s.techSkiaDesc,
            )

            techs.forEach { (name, desc) ->
                Row(Modifier.padding(vertical = 3.dp)) {
                    Text("•", color = NxTheme.colors.primary, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text       = af.maybeGibberish(name, probability = 0.20f, mode = GibberishMode.ZALGO),
                        fontWeight = FontWeight.Medium,
                        color      = NxTheme.colors.textPrimary,
                        fontSize   = 13.sp,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text     = "— ${af.maybeGibberish(desc, probability = 0.40f, mode = GibberishMode.JARGON)}",
                        color    = NxTheme.colors.textSecondary,
                        fontSize = 13.sp,
                    )
                }
            }

            Spacer(Modifier.height(20.dp))
            SectionLabel(s.aboutSectionLicense)
            Spacer(Modifier.height(8.dp))

            Text(
                text  = af.maybeGibberish(
                    s.aboutLicenseText,
                    probability = 0.45f,
                    mode        = GibberishMode.LOREM,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = NxTheme.colors.textSecondary,
            )
        }
    }
}
