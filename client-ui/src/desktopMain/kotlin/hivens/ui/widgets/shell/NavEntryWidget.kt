package hivens.ui.widgets.shell

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import hivens.ui.Screen
import hivens.ui.customization.LocalCustomization
import hivens.ui.customization.NavSelectionStyle
import hivens.ui.easter.LocalAprilFools
import hivens.ui.icons.IconKey
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.theme.NxTheme
import hivens.ui.theme.LocalStyle
import hivens.ui.utils.GameConsoleService
import hivens.ui.widgets.toWidgetColorOrNull
import hivens.widget.api.rememberProps
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.serialization.Serializable
import org.koin.compose.koinInject

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
enum class NavTarget { Home, Library, Browse, Profile, Wardrobe, Settings, About, Console, Logout }

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
            icon         = NxIcon.Home,
            outlineSwap  = true,
            phase        = 0.0f,
            active       = screen is Screen.Home || screen is Screen.ServerSettings || screen is Screen.ServerDetails,
            onClick      = { ctx.onScreenChange(Screen.Home) },
        )
        NavTarget.Library -> NavSlot(
            icon         = NxIcon.Star,
            outlineSwap  = true,
            phase        = 0.55f,
            active       = screen is Screen.Library || screen is Screen.PackDetail,
            onClick      = { ctx.onScreenChange(Screen.Library) },
        )
        NavTarget.Browse -> NavSlot(
            icon         = NxIcon.Search,
            outlineSwap  = true,
            phase        = 1.65f,
            active       = screen is Screen.Browse || screen is Screen.CataloguePackDetail,
            onClick      = { ctx.onScreenChange(Screen.Browse) },
        )
        NavTarget.Profile -> NavSlot(
            icon         = NxIcon.Person,
            outlineSwap  = true,
            phase        = 1.1f,
            active       = screen is Screen.Profile,
            onClick      = { ctx.onScreenChange(Screen.Profile) },
        )
        NavTarget.Wardrobe -> NavSlot(
            icon         = NxIcon.Palette,
            outlineSwap  = true,
            phase        = 1.6f,
            active       = screen is Screen.Wardrobe,
            onClick      = { ctx.onScreenChange(Screen.Wardrobe) },
        )
        NavTarget.Settings -> NavSlot(
            icon         = NxIcon.Settings,
            outlineSwap  = true,
            phase        = 2.2f,
            active       = screen is Screen.Settings || screen is Screen.ThemePicker ||
                screen is Screen.BackgroundSettings,
            onClick      = { ctx.onScreenChange(Screen.Settings) },
        )
        NavTarget.About -> NavSlot(
            icon         = NxIcon.Info,
            outlineSwap  = true,
            phase        = 3.3f,
            active       = screen is Screen.About,
            onClick      = { ctx.onScreenChange(Screen.About) },
        )
        NavTarget.Console -> {
            val gameConsole: GameConsoleService = koinInject()
            NavSlot(
                icon          = NxIcon.Build,
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
                icon          = NxIcon.ExitToApp,
                phase         = 0.0f,
                active        = false,
                chaosEligible = false,
                iconTint      = NxTheme.colors.error.copy(alpha = 0.75f),
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
//
// The active-item highlight is user-configurable via [NavSelectionStyle]
// (Pill is the original Material capsule). The decoration is drawn behind /
// around the icon; the icon and decoration share the selection accent
// ([CustomizationSettings.navSelectionAccent], else the theme primary).
// [outlineSwap] marks a screen-nav entry whose idle icon switches to the
// outlined FILL-axis form when the user enables the swap; service entries
// stay filled in both states.
@Composable
private fun NavSlot(
    icon: IconKey,
    phase: Float,
    active: Boolean,
    enabled: Boolean = true,
    chaosEligible: Boolean = true,
    iconTint: Color? = null,
    outlineSwap: Boolean = false,
    onClick: () -> Unit,
) {
    val af = LocalAprilFools.current
    val cz = LocalCustomization.current
    val style = LocalStyle.current

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

    // Selection accent: the user's nav override, else the theme primary -- so
    // by default it tracks the palette / accent override. Shared by the icon
    // tint and every decoration.
    val accent = cz.navSelectionAccent?.toWidgetColorOrNull() ?: NxTheme.colors.primary
    val iconColor = when {
        iconTint != null -> iconTint
        active           -> accent
        else             -> NxTheme.colors.textSecondary.copy(alpha = if (enabled) 0.70f else 0.20f)
    }
    val iconFill = if (outlineSwap && !active && cz.navSelectionOutlineIcons) 0f else 1f

    // The rail sits over the wallpaper, so the backing needs more presence than
    // the original 13% Material indicator alpha to read as selected -- 22% keeps
    // it at least as strong as the settings side-nav (18% on a section plane).
    // LeftBar / Dot use the solid accent since they are thin marks, not a backing.
    val fill = accent.copy(alpha = 0.22f)
    val interaction = remember { MutableInteractionSource() }

    Box(Modifier.graphicsLayer { translationY = offsetY }) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // Taller than the icon so the slots tile flush (rail spacing is 0)
                // and the breathing room between buttons stays inside the hit area.
                .height(54.dp)
                .selectable(
                    selected          = active,
                    enabled           = enabled,
                    role              = Role.Tab,
                    interactionSource = interaction,
                    // The hover/press state layer is user-toggleable; off leaves
                    // the active item marked only by its NavSelectionStyle.
                    indication        = if (cz.navHoverHighlight) LocalIndication.current else null,
                    onClick           = gated,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (active) {
                when (cz.navSelectionStyle) {
                    NavSelectionStyle.Pill ->
                        Box(Modifier.size(width = 44.dp, height = 32.dp).clip(RoundedCornerShape(50)).background(fill))
                    NavSelectionStyle.Square ->
                        Box(Modifier.size(width = 40.dp, height = 36.dp).clip(RoundedCornerShape(style.buttonCorner)).background(fill))
                    NavSelectionStyle.Circle ->
                        Box(Modifier.size(40.dp).clip(CircleShape).background(fill))
                    NavSelectionStyle.LeftBar ->
                        Box(
                            Modifier
                                .align(Alignment.CenterStart)
                                .padding(start = 6.dp)
                                .size(width = 3.dp, height = 26.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(accent),
                        )
                    NavSelectionStyle.Dot ->
                        Box(
                            Modifier
                                .align(Alignment.BottomCenter)
                                .padding(bottom = 7.dp)
                                .size(5.dp)
                                .clip(CircleShape)
                                .background(accent),
                        )
                    NavSelectionStyle.None -> Unit
                }
            }
            Symbol(icon = icon,
                contentDescription = null,
                tint               = iconColor,
                fill               = iconFill,
                modifier           = Modifier.size(24.dp),
            )
        }
    }
}
