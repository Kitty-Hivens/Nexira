package hivens.ui.widgets.shell

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import hivens.accounts.api.AccountKeys
import hivens.accounts.api.SessionState
import hivens.catalogue.api.CataloguePages
import hivens.ui.components.rememberSignOutRequest
import hivens.ui.customization.LocalCustomization
import hivens.ui.customization.NavSelectionStyle
import hivens.ui.icons.IconKey
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.layers.layerTrigger
import hivens.ui.navigation.LauncherConfigs
import hivens.ui.press.selectable
import hivens.ui.widgets.Commands
import hivens.ui.widgets.Sources
import hivens.ui.widgets.toWidgetColorOrNull
import hivens.widget.api.CompositionKeys
import hivens.widget.api.rememberProps
import hivens.widget.api.rememberScopedCommand
import hivens.widget.api.rememberScopedSource
import hivens.widget.api.valueOrNull
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import kotlinx.serialization.Serializable
import hivens.ui.theme.NxInk
import hivens.ui.theme.NxColor
import hivens.ui.theme.Status

// One configurable nav-rail item. `target` selects what the item does --
// a screen navigation, the console-window toggle, or logout. The whole
// rail is a slot of these, so the editor reorders / removes / restyles
// each item and the layout graph carries the rail's shape.
//
// Per-target behaviour (icon, action, active-highlight, enable/visibility,
// tint) resolves in the composable, NOT on the
// enum: it reads composition locals + koin and the enum must stay a plain
// serializable value.
@Serializable
enum class NavTarget { Home, Library, Browse, Profile, Wardrobe, Settings, About, Console, Logout }

@Serializable
data class NavEntryProps(
    val target: NavTarget = NavTarget.Home,
)

private val LIBRARY_CONFIGS = setOf(
    LauncherConfigs.Library, LauncherConfigs.Pack, LauncherConfigs.PackVersions, LauncherConfigs.Project, LauncherConfigs.Build,
)

private val SETTINGS_CONFIGS = setOf(LauncherConfigs.Settings, LauncherConfigs.Themes, LauncherConfigs.Background)

// The session is not a need of every entry: the sign-out entry draws nothing where no
// accounts module is brought in, and the rest navigate all the same.
@Widget(
    id = "nav.entry",
    displayName = "widget.nav.entry",
    propsClass = NavEntryProps::class,
    needs = ["nav.state", "nav.show", "console.visible", "console.visible.set"],
)
@Composable
fun NavEntry(instance: WidgetInstance) {
    val p = instance.rememberProps<NavEntryProps>()
    val entry = rememberScopedSource(CompositionKeys.History).valueOrNull()?.current ?: return
    val show = rememberScopedCommand(CompositionKeys.Show) ?: return
    val config = entry.config

    when (p.target) {
        NavTarget.Home -> NavSlot(
            icon         = NxIcon.Home,
            outlineSwap  = true,
            active       = config == LauncherConfigs.Home,
            onClick      = { show(LauncherConfigs.section(LauncherConfigs.Home)) },
        )
        NavTarget.Library -> NavSlot(
            icon         = NxIcon.Star,
            outlineSwap  = true,
            // A project page and a build's page belong here too: both are opened
            // from a pack's content tab, so the rail went dark the moment a reader
            // followed a mod out of the pack they were standing in.
            active       = config in LIBRARY_CONFIGS,
            onClick      = { show(LauncherConfigs.section(LauncherConfigs.Library)) },
        )
        NavTarget.Browse -> NavSlot(
            icon         = NxIcon.Search,
            outlineSwap  = true,
            // Not from inside a pack: that browse is the pack's, and the catalogue's own is another page.
            active       = config == LauncherConfigs.CataloguePack ||
                (config == LauncherConfigs.Browse && CataloguePages.Into.id !in entry.opened),
            onClick      = { show(LauncherConfigs.section(LauncherConfigs.Browse)) },
        )
        NavTarget.Profile -> NavSlot(
            icon         = NxIcon.Person,
            outlineSwap  = true,
            active       = config == LauncherConfigs.Profile,
            onClick      = { show(LauncherConfigs.section(LauncherConfigs.Profile)) },
        )
        NavTarget.Wardrobe -> NavSlot(
            icon         = NxIcon.Palette,
            outlineSwap  = true,
            active       = config == LauncherConfigs.Wardrobe,
            onClick      = { show(LauncherConfigs.section(LauncherConfigs.Wardrobe)) },
        )
        NavTarget.Settings -> NavSlot(
            icon         = NxIcon.Settings,
            outlineSwap  = true,
            active       = config in SETTINGS_CONFIGS,
            onClick      = { show(LauncherConfigs.section(LauncherConfigs.Settings)) },
        )
        NavTarget.About -> NavSlot(
            icon         = NxIcon.Info,
            outlineSwap  = true,
            active       = config == LauncherConfigs.About,
            onClick      = { show(LauncherConfigs.section(LauncherConfigs.About)) },
        )
        NavTarget.Console -> {
            val visible = rememberScopedSource(Sources.ConsoleVisible).valueOrNull() ?: return
            val setVisible = rememberScopedCommand(Commands.SetConsoleVisible) ?: return
            NavSlot(
                icon    = NxIcon.Build,
                active  = visible,
                onClick = { setVisible(!visible) },
            )
        }
        NavTarget.Logout -> {
            // Self-gates on the session: render nothing when unauthenticated
            // so the slot lists the entry unconditionally and the graph stays
            // free of auth-state vocabulary.
            val appState = rememberScopedSource(AccountKeys.Session).valueOrNull()
            if (appState !is SessionState.SignedIn) return
            // Asks first, through the window's sign-out question.
            val signOut = rememberSignOutRequest() ?: return
            NavSlot(
                icon     = NxIcon.ExitToApp,
                active   = false,
                iconTint = NxColor.status(Status.Error).copy(alpha = 0.75f),
                onClick  = signOut,
            )
        }
    }
}

// Shared rail-item render. Screen-agnostic: callers pass a resolved onClick
// plus active/enabled flags, so service actions (Console / Logout) reuse it
// just like screen-nav targets. [iconTint] overrides the rail tint (logout's
// destructive red); null keeps the default.
//
// The active-item highlight is user-configurable via [NavSelectionStyle]
// (Pill is the original Material capsule). The decoration is drawn behind /
// around the icon; the icon and decoration share the selection accent
// ([CustomizationSettings.navSelectionAccent], else the theme primary).
// [outlineSwap] marks a screen-nav entry whose idle icon switches to the
// outlined FILL-axis form when the user enables the swap; service entries
// stay filled in both states.
@Composable
internal fun NavSlot(
    icon: IconKey,
    active: Boolean,
    enabled: Boolean = true,
    iconTint: Color? = null,
    outlineSwap: Boolean = false,
    onClick: () -> Unit,
) {
    val cz = LocalCustomization.current

    // Selection accent: the user's nav override, else the theme primary -- so
    // by default it tracks the palette / accent override. Shared by the icon
    // tint and every decoration.
    val accent = cz.navSelectionAccent?.toWidgetColorOrNull() ?: NxColor.lead()
    val iconColor = when {
        iconTint != null -> iconTint
        active           -> accent
        else             -> NxInk.quiet.copy(alpha = if (enabled) 0.70f else 0.20f)
    }
    val iconFill = if (outlineSwap && !active && cz.navSelectionOutlineIcons) 0f else 1f

    // The rail sits over the wallpaper, so the backing needs more presence than
    // the original 13% Material indicator alpha to read as selected -- 22% keeps
    // it at least as strong as the settings side-nav (18% on a section plane).
    // LeftBar / Dot use the solid accent since they are thin marks, not a backing.
    val fill = accent.copy(alpha = 0.22f)
    val interaction = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            // Taller than the icon so the slots tile flush (rail spacing is 0)
            // and the breathing room between buttons stays inside the hit area.
            .height(54.dp)
            // Sign out asks first, in a dialog that gives the keyboard back here.
            .layerTrigger()
            .selectable(
                selected          = active,
                enabled           = enabled,
                role              = Role.Tab,
                interactionSource = interaction,
                // The hover/press state layer is user-toggleable; off leaves
                // the active item marked only by its NavSelectionStyle.
                indication        = if (cz.navHoverHighlight) LocalIndication.current else null,
                onClick           = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (active) {
            when (cz.navSelectionStyle) {
                NavSelectionStyle.Pill ->
                    Box(Modifier.size(width = 44.dp, height = 32.dp).clip(RoundedCornerShape(50)).background(fill))
                NavSelectionStyle.Square ->
                    Box(Modifier.size(width = 40.dp, height = 36.dp).clip(MaterialTheme.shapes.small).background(fill))
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
