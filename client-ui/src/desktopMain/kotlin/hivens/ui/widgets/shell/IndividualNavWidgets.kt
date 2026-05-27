package hivens.ui.widgets.shell

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import hivens.ui.Screen
import hivens.ui.easter.LocalAprilFools
import hivens.ui.theme.CelestiaTheme
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import kotlin.math.sin
import kotlin.random.Random

// Individual nav widgets. The bundled LeftRailNavButtons stays as the
// out-of-box default for users who never touch the editor; these six
// let editor users selectively keep / remove / reorder nav items. To
// build a "music-only" launcher (project_achievements vision), strip
// LeftRailNavButtons, keep just NavSettings + NavHome, fill main
// with MusicPlayerWidget + ClockWidget. Done.
//
// Settings is the only non-removable individual nav -- removing all
// nav including Settings would brick the launcher (no way back to
// reset the layout); Settings stays as the always-accessible escape.

@Widget(id = "nav.home", displayName = "Nav: Home")
@Composable
fun NavHomeWidget(instance: WidgetInstance) {
    NavSlot(
        icon       = Icons.Default.Home,
        screen     = Screen.Home,
        phase      = 0.0f,
        isActive   = { it is Screen.Home || it is Screen.ServerSettings || it is Screen.ServerDetails },
    )
}

@Widget(id = "nav.library", displayName = "Nav: Library")
@Composable
fun NavLibraryWidget(instance: WidgetInstance) {
    NavSlot(
        icon     = Icons.Default.Star,
        screen   = Screen.Library,
        phase    = 0.55f,
        isActive = { it is Screen.Library || it is Screen.PackDetail },
    )
}

@Widget(id = "nav.browse", displayName = "Nav: Browse")
@Composable
fun NavBrowseWidget(instance: WidgetInstance) {
    NavSlot(
        icon     = Icons.Default.Search,
        screen   = Screen.Browse,
        phase    = 1.65f,
        isActive = { it is Screen.Browse || it is Screen.BrowsePackDetail },
    )
}

@Widget(id = "nav.profile", displayName = "Nav: Profile")
@Composable
fun NavProfileWidget(instance: WidgetInstance) {
    NavSlot(
        icon            = Icons.Default.Person,
        screen          = Screen.Profile,
        phase           = 1.1f,
        isActive        = { it is Screen.Profile },
        enabledOverride = { _, isAuthed -> isAuthed },
    )
}

@Widget(id = "nav.settings", displayName = "Nav: Settings", removable = false)
@Composable
fun NavSettingsWidget(instance: WidgetInstance) {
    NavSlot(
        icon     = Icons.Default.Settings,
        screen   = Screen.Settings,
        phase    = 2.2f,
        isActive = {
            it is Screen.Settings || it is Screen.ThemePicker ||
            it is Screen.BackgroundSettings || it is Screen.CustomizationExtension
        },
    )
}

@Widget(id = "nav.about", displayName = "Nav: About")
@Composable
fun NavAboutWidget(instance: WidgetInstance) {
    NavSlot(
        icon     = Icons.Default.Info,
        screen   = Screen.About,
        phase    = 3.3f,
        isActive = { it is Screen.About },
    )
}

// Shared nav-item render. Captures the April Fools chaos +
// out-of-phase bounce so each individual widget keeps the bundled
// LeftRailNavButtons feel.
@Composable
private fun NavSlot(
    icon: ImageVector,
    screen: Screen,
    phase: Float,
    isActive: (Screen) -> Boolean,
    enabledOverride: ((Screen, Boolean) -> Boolean)? = null,
) {
    val ctx = LocalLeftRailContext.current
    val af = LocalAprilFools.current

    val bounceAmp = if (af.isActive()) af.intensity() * 18f else 0f
    val transition = rememberInfiniteTransition(label = "nav-bounce-$phase")
    val cycle by transition.animateFloat(
        initialValue  = 0f,
        targetValue   = (2f * Math.PI).toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (af.isActive())
                    (2200 - af.intensity() * 1400).toInt().coerceAtLeast(600)
                else 2200,
                easing = LinearEasing,
            ),
            repeatMode = RepeatMode.Restart,
        ),
        label = "nav-bounce-cycle-$phase",
    )
    val offsetY = sin(cycle + phase) * bounceAmp

    val click = {
        if (!af.isActive() || Random.nextFloat() > 0.30f) {
            ctx.onScreenChange(screen)
        }
    }
    val enabled = enabledOverride?.invoke(ctx.currentScreen, ctx.isAuthenticated) ?: true

    Box(Modifier.graphicsLayer { translationY = offsetY }) {
        NavigationRailItem(
            icon = {
                Icon(
                    imageVector        = icon,
                    contentDescription = null,
                    modifier           = Modifier.size(24.dp),
                )
            },
            selected        = isActive(ctx.currentScreen),
            onClick         = click,
            enabled         = enabled,
            label           = null,
            alwaysShowLabel = false,
            colors          = NavigationRailItemDefaults.colors(
                selectedIconColor   = CelestiaTheme.colors.primary,
                unselectedIconColor = CelestiaTheme.colors.textSecondary.copy(
                    alpha = if (enabled) 0.70f else 0.20f,
                ),
                indicatorColor      = CelestiaTheme.colors.primary.copy(alpha = 0.13f),
            ),
        )
    }
}
