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
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Build
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import hivens.ui.Screen
import hivens.ui.easter.LocalAprilFools
import hivens.ui.theme.CelestiaTheme
import hivens.ui.utils.GameConsoleService
import hivens.widget.api.rememberProps
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject
import kotlin.math.sin
import kotlin.random.Random

// One configurable nav-rail item. `target` selects what the item does --
// a screen navigation, the console-window toggle, or logout. The whole
// rail is a slot of these, so the editor reorders / removes / restyles
// each item and the layout graph carries the rail's shape.
//
// Per-target behaviour (icon, action, active-highlight, enable/visibility,
// tint, April Fools eligibility) resolves in the composable, NOT on the
// enum: it reads composition locals + koin and the enum must stay a plain
// serializable value.
@Serializable
enum class NavTarget { Home, Library, Browse, Profile, Settings, About, Console, Logout }

@Serializable
data class NavEntryProps(
    val target: NavTarget = NavTarget.Home,
)

@Widget(id = "nav.entry", displayName = "widget.nav.entry", propsClass = NavEntryProps::class)
@Composable
fun NavEntry(instance: WidgetInstance) {
    val p = instance.rememberProps<NavEntryProps>()
    val ctx = LocalLeftRailContext.current
    val screen = ctx.currentScreen

    when (p.target) {
        NavTarget.Home -> NavSlot(
            icon    = Icons.Default.Home,
            phase   = 0.0f,
            active  = screen is Screen.Home || screen is Screen.ServerSettings || screen is Screen.ServerDetails,
            onClick = { ctx.onScreenChange(Screen.Home) },
        )
        NavTarget.Library -> NavSlot(
            icon    = Icons.Default.Star,
            phase   = 0.55f,
            active  = screen is Screen.Library || screen is Screen.PackDetail,
            onClick = { ctx.onScreenChange(Screen.Library) },
        )
        NavTarget.Browse -> NavSlot(
            icon    = Icons.Default.Search,
            phase   = 1.65f,
            active  = screen is Screen.Browse || screen is Screen.BrowsePackDetail,
            onClick = { ctx.onScreenChange(Screen.Browse) },
        )
        NavTarget.Profile -> NavSlot(
            icon    = Icons.Default.Person,
            phase   = 1.1f,
            active  = screen is Screen.Profile,
            enabled = ctx.isAuthenticated,
            onClick = { ctx.onScreenChange(Screen.Profile) },
        )
        NavTarget.Settings -> NavSlot(
            icon    = Icons.Default.Settings,
            phase   = 2.2f,
            active  = screen is Screen.Settings || screen is Screen.ThemePicker ||
                screen is Screen.BackgroundSettings || screen is Screen.CustomizationExtension,
            onClick = { ctx.onScreenChange(Screen.Settings) },
        )
        NavTarget.About -> NavSlot(
            icon    = Icons.Default.Info,
            phase   = 3.3f,
            active  = screen is Screen.About,
            onClick = { ctx.onScreenChange(Screen.About) },
        )
        NavTarget.Console -> {
            val gameConsole: GameConsoleService = koinInject()
            NavSlot(
                icon          = Icons.Default.Build,
                phase         = 0.0f,
                active        = gameConsole.shouldShowConsole,
                chaosEligible = false,
                onClick       = { if (gameConsole.shouldShowConsole) gameConsole.hide() else gameConsole.show() },
            )
        }
        NavTarget.Logout -> {
            // Self-gates on the session: render nothing when unauthenticated
            // so the slot lists the entry unconditionally and the graph stays
            // free of auth-state vocabulary.
            if (!ctx.isAuthenticated) return
            NavSlot(
                icon          = Icons.AutoMirrored.Filled.ExitToApp,
                phase         = 0.0f,
                active        = false,
                chaosEligible = false,
                iconTint      = CelestiaTheme.colors.error.copy(alpha = 0.75f),
                onClick       = ctx.onLogout,
            )
        }
    }
}

// Shared rail-item render. Screen-agnostic: callers pass a resolved onClick
// plus active/enabled flags, so service actions (Console / Logout) reuse it
// just like screen-nav targets. [chaosEligible] gates the April Fools
// behaviour -- the 30% click-swallow AND the out-of-phase bounce -- so the
// console toggle and logout stay stable and always work. [iconTint] overrides
// the rail tint (logout's destructive red); null keeps the default.
@Composable
private fun NavSlot(
    icon: ImageVector,
    phase: Float,
    active: Boolean,
    enabled: Boolean = true,
    chaosEligible: Boolean = true,
    iconTint: Color? = null,
    onClick: () -> Unit,
) {
    val af = LocalAprilFools.current

    val bounceAmp = if (chaosEligible && af.isActive()) af.intensity() * 18f else 0f
    // Only run the infinite transition during April Fools. Otherwise a 0-amplitude
    // bounce still subscribes to an infinite-transition State and recomposes every
    // rail item every frame forever -- the rail is mounted app-wide, so the
    // launcher never reaches Compose idle (perpetual 60fps wakeups, battery drain).
    val offsetY = if (bounceAmp != 0f) {
        val transition = rememberInfiniteTransition(label = "nav-bounce-$phase")
        val cycle by transition.animateFloat(
            initialValue  = 0f,
            targetValue   = (2f * Math.PI).toFloat(),
            animationSpec = infiniteRepeatable(
                animation = tween(
                    durationMillis = (2200 - af.intensity() * 1400).toInt().coerceAtLeast(600),
                    easing = LinearEasing,
                ),
                repeatMode = RepeatMode.Restart,
            ),
            label = "nav-bounce-cycle-$phase",
        )
        sin(cycle + phase) * bounceAmp
    } else {
        0f
    }

    val gated = {
        if (!chaosEligible || !af.isActive() || Random.nextFloat() > 0.30f) onClick()
    }

    Box(Modifier.graphicsLayer { translationY = offsetY }) {
        NavigationRailItem(
            icon = {
                Icon(
                    imageVector        = icon,
                    contentDescription = null,
                    modifier           = Modifier.size(24.dp),
                )
            },
            selected        = active,
            onClick         = gated,
            enabled         = enabled,
            label           = null,
            alwaysShowLabel = false,
            colors          = NavigationRailItemDefaults.colors(
                selectedIconColor   = iconTint ?: CelestiaTheme.colors.primary,
                unselectedIconColor = iconTint ?: CelestiaTheme.colors.textSecondary.copy(
                    alpha = if (enabled) 0.70f else 0.20f,
                ),
                indicatorColor      = CelestiaTheme.colors.primary.copy(alpha = 0.13f),
            ),
        )
    }
}
