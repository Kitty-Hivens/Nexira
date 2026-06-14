package hivens.ui.widgets.about

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import hivens.config.Branding
import hivens.ui.BuildConfig
import hivens.ui.components.GlassCard
import hivens.ui.easter.GibberishMode
import hivens.ui.easter.LocalAprilFools
import hivens.ui.generated.resources.Res
import hivens.ui.generated.resources.favicon
import hivens.ui.i18n.LocalStrings
import hivens.ui.theme.CelestiaTheme
import hivens.widget.api.rememberProps
import hivens.widget.model.PropLabel
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import kotlinx.serialization.Serializable
import org.jetbrains.compose.resources.painterResource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// Logo + title + version + build-date + description card. The
// AprilFools wrapper occasionally corrupts these strings -- left
// column embraces the chaos. Right column (system info, links)
// stays readable on purpose.
@Serializable
data class AboutLogoProps(
    @PropLabel("widget.about.logo.title") val title: String = "",
)

@Widget(id = "about.logo", displayName = "widget.about.logo", propsClass = AboutLogoProps::class)
@Composable
fun AboutLogoWidget(instance: WidgetInstance) {
    val p = instance.rememberProps<AboutLogoProps>()
    val af = LocalAprilFools.current
    val s = LocalStrings.current

    GlassCard(Modifier.fillMaxWidth()) {
        Column(
            modifier            = Modifier.fillMaxWidth().padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Image(
                painter            = painterResource(Res.drawable.favicon),
                contentDescription = s.aboutLogoDesc,
                modifier           = Modifier.size(86.dp),
            )
            Spacer(Modifier.height(16.dp))

            Text(
                text       = af.maybeGibberish(p.title.ifBlank { Branding.TITLE }, probability = 0.15f),
                style      = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Black,
                color      = CelestiaTheme.colors.textPrimary,
            )
            Spacer(Modifier.height(8.dp))

            Box(
                modifier = Modifier
                    .clip(MaterialTheme.shapes.extraSmall)
                    .background(CelestiaTheme.colors.primary.copy(alpha = 0.15f))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            ) {
                Text(
                    text       = af.maybeGibberish(
                        "v${Branding.VERSION.removePrefix("v")}",
                        probability = 0.30f,
                        mode        = GibberishMode.FAKE_VER,
                    ),
                    style      = MaterialTheme.typography.labelLarge,
                    color      = CelestiaTheme.colors.primary,
                    fontWeight = FontWeight.Bold,
                )
            }

            val buildDate = remember {
                runCatching {
                    SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault())
                        .format(Date(BuildConfig.BUILD_TIME))
                }.getOrDefault("--")
            }

            Text(
                text  = af.maybeGibberish(s.aboutBuildDate(buildDate), probability = 0.25f),
                style = MaterialTheme.typography.bodySmall,
                color = CelestiaTheme.colors.textSecondary.copy(alpha = 0.6f),
            )
            Spacer(Modifier.height(16.dp))

            Text(
                text      = af.maybeGibberish(
                    s.aboutDescription(Branding.UPSTREAM_NAME),
                    probability = 0.20f,
                ),
                style     = MaterialTheme.typography.bodyMedium,
                color     = CelestiaTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}
