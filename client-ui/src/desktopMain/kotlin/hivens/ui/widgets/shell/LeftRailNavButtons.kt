package hivens.ui.widgets.shell

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
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

@Widget(id = "appshell.leftrail.navbuttons", displayName = "Sidebar nav buttons")
@Composable
fun LeftRailNavButtons(instance: WidgetInstance) {
    val ctx = LocalLeftRailContext.current
    val af = LocalAprilFools.current

    val homeActive = ctx.currentScreen is Screen.Home ||
        ctx.currentScreen is Screen.ServerSettings ||
        ctx.currentScreen is Screen.ServerDetails
    val libraryActive = ctx.currentScreen is Screen.Library ||
        ctx.currentScreen is Screen.PackDetail
    val browseActive = ctx.currentScreen is Screen.Browse ||
        ctx.currentScreen is Screen.BrowsePackDetail
    val profileActive = ctx.currentScreen is Screen.Profile
    val settingsActive = ctx.currentScreen is Screen.Settings ||
        ctx.currentScreen is Screen.ThemePicker ||
        ctx.currentScreen is Screen.BackgroundSettings ||
        ctx.currentScreen is Screen.CustomizationExtension
    val aboutActive = ctx.currentScreen is Screen.About

    // April Fools: nav clicks have a 30% chance of being silently
    // swallowed. The button doesn't move or react -- it just feels
    // like the UI froze. Logout is intentionally excluded so the
    // user can always escape.
    fun chaosNavClick(originalClick: () -> Unit): () -> Unit {
        if (!af.isActive()) return originalClick
        return {
            if (Random.nextFloat() > 0.30f) originalClick()
        }
    }

    // April Fools: bouncing nav buttons. Each item has a unique sine
    // phase so they bounce out of sync. Amplitude grows from 0px on
    // day 1 to 18px on day 14.
    val bounceAmplitude = if (af.isActive()) af.intensity() * 18f else 0f
    val bounceTransition = rememberInfiniteTransition(label = "navBounce")
    val bounceCycle by bounceTransition.animateFloat(
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
        label = "bounceCycle",
    )

    val homeOffset     = sin(bounceCycle + 0.0f)  * bounceAmplitude
    val libraryOffset  = sin(bounceCycle + 0.55f) * bounceAmplitude
    val browseOffset   = sin(bounceCycle + 1.65f) * bounceAmplitude
    val profileOffset  = sin(bounceCycle + 1.1f)  * bounceAmplitude
    val settingsOffset = sin(bounceCycle + 2.2f)  * bounceAmplitude
    val aboutOffset    = sin(bounceCycle + 3.3f)  * bounceAmplitude

    Spacer(Modifier.height(8.dp))

    Box(Modifier.graphicsLayer { translationY = homeOffset }) {
        SidebarNavItem(
            icon     = Icons.Default.Home,
            selected = homeActive,
            onClick  = chaosNavClick { ctx.onScreenChange(Screen.Home) },
        )
    }
    Box(Modifier.graphicsLayer { translationY = libraryOffset }) {
        SidebarNavItem(
            icon     = Icons.Default.Star,
            selected = libraryActive,
            onClick  = chaosNavClick { ctx.onScreenChange(Screen.Library) },
        )
    }
    Box(Modifier.graphicsLayer { translationY = browseOffset }) {
        SidebarNavItem(
            icon     = Icons.Default.Search,
            selected = browseActive,
            onClick  = chaosNavClick { ctx.onScreenChange(Screen.Browse) },
        )
    }
    Box(Modifier.graphicsLayer { translationY = profileOffset }) {
        SidebarNavItem(
            icon     = Icons.Default.Person,
            selected = profileActive,
            enabled  = ctx.isAuthenticated,
            onClick  = chaosNavClick { ctx.onScreenChange(Screen.Profile) },
        )
    }
    Box(Modifier.graphicsLayer { translationY = settingsOffset }) {
        SidebarNavItem(
            icon     = Icons.Default.Settings,
            selected = settingsActive,
            onClick  = chaosNavClick { ctx.onScreenChange(Screen.Settings) },
        )
    }
    Box(Modifier.graphicsLayer { translationY = aboutOffset }) {
        SidebarNavItem(
            icon     = Icons.Default.Info,
            selected = aboutActive,
            onClick  = chaosNavClick { ctx.onScreenChange(Screen.About) },
        )
    }
}

@Composable
private fun SidebarNavItem(
    icon: ImageVector,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    NavigationRailItem(
        icon = {
            Icon(
                imageVector        = icon,
                contentDescription = null,
                modifier           = Modifier.size(24.dp),
            )
        },
        selected        = selected,
        onClick         = onClick,
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
