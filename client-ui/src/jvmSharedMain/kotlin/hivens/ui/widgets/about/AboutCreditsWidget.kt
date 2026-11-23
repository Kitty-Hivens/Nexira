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
import coil3.compose.AsyncImage
import hivens.ui.BuildConfig
import hivens.ui.i18n.nexiraStrings
import hivens.widget.api.rememberProps
import hivens.widget.model.PropLabel
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import kotlinx.serialization.Serializable
import hivens.ui.theme.NxInk
import hivens.ui.theme.NxColor

// Credits + technology list + license. Self-scrolls because the
// content is long; the surface does not impose a verticalScroll on
// the slot.
@Serializable
data class AboutCreditsProps(
    // Overrides only the top "Авторы" section header; the Технологии and
    // Лицензия sections stay localized.
    @PropLabel("widget.about.credits.title") val title: String = "",
)

// The ceiling is load-bearing rather than cosmetic: this widget scrolls its own
// content, and Compose refuses to measure a scrolling component against an
// unbounded height. The editor can put it in a slot that has none, and the
// renderer fills that silence from here.
@Widget(
    id = "about.credits",
    displayName = "widget.about.credits",
    propsClass = AboutCreditsProps::class,
    surface = """{"fill":"card","border":{"widthDp":1.0}}""",
    minWidth = 260, minHeight = 180,
    prefWidth = 320, prefHeight = 428,
    maxWidth = 720, maxHeight = 1200,
)
@Composable
fun AboutCreditsWidget(instance: WidgetInstance) {
    val p = instance.rememberProps<AboutCreditsProps>()
    val s = nexiraStrings()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        SectionLabel(p.title.ifBlank { s.text("aboutSectionCreator") })
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
                    color      = NxInk.main,
                )
                Text(
                    text  = "Architect & Developer",
                    style = MaterialTheme.typography.bodySmall,
                    color = NxColor.lead(),
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        SectionLabel(s.text("aboutSectionTechnologies"))
        Spacer(Modifier.height(8.dp))

        val techs = listOf(
            "Kotlin ${KotlinVersion.CURRENT}"      to s.text("techKotlinDesc"),
            "Compose ${BuildConfig.COMPOSE_VERSION}" to s.text("techComposeDesc"),
            "Ktor ${BuildConfig.KTOR_VERSION}"     to s.text("techKtorDesc"),
            "Koin ${BuildConfig.KOIN_VERSION}"     to s.text("techKoinDesc"),
            "Coil ${BuildConfig.COIL_VERSION}"     to s.text("techCoilDesc"),
            "Skia (Skiko)"                          to s.text("techSkiaDesc"),
        )

        techs.forEach { (name, desc) ->
            Row(Modifier.padding(vertical = 3.dp)) {
                Text("•", color = NxColor.lead(), fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(8.dp))
                Text(
                    text       = name,
                    fontWeight = FontWeight.Medium,
                    color      = NxInk.main,
                    style      = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text     = "— $desc",
                    color    = NxInk.quiet,
                    style    = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        Spacer(Modifier.height(20.dp))
        SectionLabel(s.text("aboutSectionLicense"))
        Spacer(Modifier.height(8.dp))

        Text(
            text  = s.text("aboutLicenseText"),
            style = MaterialTheme.typography.bodySmall,
            color = NxInk.quiet,
        )
    }
}
