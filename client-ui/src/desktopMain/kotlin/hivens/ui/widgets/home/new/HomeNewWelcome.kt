package hivens.ui.widgets.home.new

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.ui.AppState
import hivens.ui.customization.glassSurfaceAlpha
import hivens.ui.i18n.LocalStrings
import hivens.ui.theme.NxTheme
import hivens.widget.api.rememberProps
import hivens.widget.model.PropLabel
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import kotlinx.serialization.Serializable

@Serializable
data class WelcomeProps(
    // Blank keeps the localized, name-personalized greeting; a non-blank
    // value is the user's own banner text (single language, by choice).
    @PropLabel("widget.home.new.welcome.customGreeting") val customGreeting: String = "",
    @PropLabel("widget.home.new.welcome.showSubtitle") val showSubtitle: Boolean = true,
)

// Greeting banner. Personalizes with the authenticated player name;
// in unauthenticated / loading states the welcome stays generic so the
// banner does not flicker between greetings during login.
@Widget(id = "home.new.welcome", displayName = "widget.home.new.welcome", propsClass = WelcomeProps::class)
@Composable
fun HomeNewWelcome(instance: WidgetInstance) {
    val p = instance.rememberProps<WelcomeProps>()
    val ctx = LocalHomeNewContext.current
    val s = LocalStrings.current
    val playerName = (ctx.appState as? AppState.Authenticated)?.session?.playerName

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(glassSurfaceAlpha(0.45f))
            .padding(horizontal = 20.dp, vertical = 18.dp),
    ) {
        Text(
            text       = when {
                p.customGreeting.isNotBlank() -> p.customGreeting
                playerName != null            -> s.dashboardWelcome(playerName)
                else                          -> s.appName
            },
            style      = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color      = NxTheme.colors.textPrimary,
        )
        if (p.showSubtitle) {
            Spacer(Modifier.height(4.dp))
            Text(
                text  = s.settingsHomeViewSub,
                style = MaterialTheme.typography.bodySmall,
                color = NxTheme.colors.textSecondary,
            )
        }
    }
}
