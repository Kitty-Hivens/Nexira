package hivens.look.colour.nx.controls

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.i18n.api.strings
import hivens.themes.api.ThemeMode
import hivens.themes.api.ThemesKeys
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.nx.NxChoiceChip
import hivens.ui.nx.NxRow
import hivens.ui.nx.NxSectionHeader
import hivens.ui.nx.NxSwitch
import hivens.ui.puppet.PuppetClick
import hivens.ui.puppet.PuppetToggle
import hivens.ui.theme.LocalThemeReveal
import hivens.ui.theme.Motion
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxInk
import hivens.widget.api.rememberScopedCommand
import hivens.widget.api.rememberScopedSource
import hivens.widget.api.valueOrNull
import hivens.pages.api.ScreenId
import hivens.pages.api.Show
import hivens.widget.model.Widget
import hivens.pages.api.PagesKeys

// The switches that steer the nx colour provider, each a widget a layout places where the host
// leaves a slot for it. Each is shown under NX_DRAWING_ID, so a slot does not place it while nx does
// not draw the colour, and each draws nothing where the themes module's key it shows has no answer,
// since a switch that steers nothing is clutter. None declares the themes module's keys as its needs
// for the same reason: a need with no answer draws the misplaced placeholder, where these are to be
// absent. Already there when their page opens, as the rows around them are.

/** The screen the picker link shows: the theme picker's. */
private val PICKER_SCREEN = ScreenId("theme.picker")

// Day or night by the person's own hand, which hands what decides it back to the manual mode. The
// puppet id is the one the settings screen had, kept so a driver finds the switch where it was.
@Widget(id = "theme.daynight", displayName = "widget.theme.daynight", enter = "none", shownWhen = NX_DRAWING_ID)
@Composable
fun DayNightWidget() {
    val dark = rememberScopedSource(ThemesKeys.Dark).valueOrNull() ?: return
    val setDark = rememberScopedCommand(ThemesKeys.SetDark)
    val s = strings(ColourNxControls.ID)
    // Optimistic, so the switch flips at once while the reveal runs. Keyed on the source, so a flip
    // the system or the wallpaper made moves it too.
    var shown by remember(dark) { mutableStateOf(dark) }
    val flip: (Boolean) -> Unit = { checked ->
        shown = checked
        setDark?.invoke(!dark)
    }
    DayNightRow(checked = shown, title = s.text("settingsDarkTheme"), description = s.text("settingsDarkThemeDesc"), onCheckedChange = flip)
    PuppetToggle("settings.darkTheme", shown, onValueChange = flip)
}

// A theme without a light scheme stays dark in light mode rather than having one invented for it,
// so this says why the switch changed nothing. Nothing at all for a theme that has one.
@Widget(id = "theme.darkonly", displayName = "widget.theme.darkonly", enter = "none", shownWhen = NX_DRAWING_ID)
@Composable
fun DarkOnlyWidget() {
    val active = rememberScopedSource(ThemesKeys.Active).valueOrNull() ?: return
    if (active.hasLight) return
    Text(
        text  = strings(ColourNxControls.ID).text("themePickerDarkOnly"),
        style = MaterialTheme.typography.bodySmall,
        color = NxInk.quiet,
    )
}

// What decides day and night: the person's hand, the system's scheme where the host can read one,
// or the wallpaper's brightness. Following the system is offered, and greyed out with a line saying
// why, where the system's scheme cannot be read.
@Widget(id = "theme.mode", displayName = "widget.theme.mode", enter = "none", shownWhen = NX_DRAWING_ID)
@Composable
fun ThemeModeWidget() {
    val mode = rememberScopedSource(ThemesKeys.Mode).valueOrNull() ?: return
    val systemAvailable = rememberScopedSource(ThemesKeys.SystemSchemeAvailable).valueOrNull() ?: false
    val setMode = rememberScopedCommand(ThemesKeys.SetMode) ?: {}
    val s = strings(ColourNxControls.ID)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            NxSectionHeader(s.text("settingsThemeModeTitle"))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement   = Arrangement.spacedBy(8.dp),
            ) {
                NxChoiceChip(s.text("settingsThemeModeManual"), mode == ThemeMode.Manual) {
                    setMode(ThemeMode.Manual)
                }
                NxChoiceChip(
                    s.text("settingsThemeModeSystem"),
                    mode == ThemeMode.System,
                    enabled = systemAvailable,
                ) { setMode(ThemeMode.System) }
                NxChoiceChip(s.text("settingsThemeModeWallpaper"), mode == ThemeMode.Wallpaper) {
                    setMode(ThemeMode.Wallpaper)
                }
            }
        }
        if (!systemAvailable) {
            Text(
                text  = s.text("settingsThemeModeSystemUnavailable"),
                style = MaterialTheme.typography.bodySmall,
                color = NxInk.quiet,
            )
        }
    }
}

// The way to the theme picker, which shows its screen in the composition this is drawn in. The puppet
// id is the one the settings screen had.
@Widget(id = "theme.picker.link", displayName = "widget.theme.picker.link", enter = "none", shownWhen = NX_DRAWING_ID)
@Composable
fun ThemePickerLinkWidget() {
    val show = rememberScopedCommand(PagesKeys.Show)
    val s = strings(ColourNxControls.ID)
    val open: () -> Unit = { show?.invoke(Show(PICKER_SCREEN)) }
    PuppetClick("settings.openThemePicker") { open() }
    NxRow(
        title    = s.text("settingsThemePicker"),
        subtitle = s.text("settingsThemePickerSub"),
        icon     = NxIcon.Star,
        iconTint = NxColor.lead(),
        onClick  = open,
        trailing = { Symbol(NxIcon.ChevronRight, null, tint = NxColor.lead()) },
    )
}

// Static day and night colours: the sun stays warm orange and the moon cool blue whatever the theme,
// so the switch reads as day and night at a glance.
private val SunOrange = Color(0xFFFF8C00)
private val MoonBlue  = Color(0xFF8AB4F8)

/**
 * The dark theme switch as an in-plane row with a day and night identity: the sun, warm, when light,
 * the moon, cool, when dark. Icon and switch track take that fixed colour, not the theme's lead
 * colour. The flip runs through the reveal, a circle growing out of the switch, where a host provides
 * one, and is a plain flip where none does or motion is reduced.
 */
@Composable
internal fun DayNightRow(
    checked: Boolean,
    title: String,
    description: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    val tint = if (checked) MoonBlue else SunOrange
    val reveal = LocalThemeReveal.current
    // The look changing is the look's own role. Where the look holds it still there is no wipe at all,
    // and the reveal reads that as a plain flip.
    val change = Motion.lookChange
    var switchOrigin by remember { mutableStateOf(Offset.Zero) }
    val onToggle: (Boolean) -> Unit = { newValue ->
        if (reveal != null) reveal.reveal(switchOrigin, change) { onCheckedChange(newValue) }
        else onCheckedChange(newValue)
    }
    Row(
        modifier              = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Symbol(if (checked) NxIcon.DarkMode else NxIcon.LightMode, null, tint = tint, size = 22.dp)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, color = NxInk.main, fontWeight = FontWeight.Medium)
                Text(description, style = MaterialTheme.typography.bodySmall, color = NxInk.quiet)
            }
        }
        Spacer(Modifier.width(12.dp))
        NxSwitch(
            checked         = checked,
            onCheckedChange = onToggle,
            accent          = tint,
            modifier        = Modifier.onGloballyPositioned { switchOrigin = it.boundsInWindow().center },
        )
    }
}
