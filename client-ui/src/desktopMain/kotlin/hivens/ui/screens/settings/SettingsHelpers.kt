package hivens.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.core.data.HomeView
import hivens.core.data.UiStyle
import hivens.ui.components.NxSwitch
import hivens.ui.customization.glassSurfaceAlpha
import hivens.ui.icons.IconKey
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.theme.NxTheme
import hivens.ui.theme.LocalStyle
import hivens.ui.theme.LocalThemeReveal

/**
 * Glass-tinted panel background for every Settings row / picker /
 * card. Opted out of [LocalStyle.cardSurface] -- the Settings page
 * stays neutral; Flat treatment applies only to content surfaces
 * (GlassCard, library cards) elsewhere.
 */
@Composable
internal fun settingsRowBackground(): Color =
    NxTheme.colors.background.copy(alpha = 0.4f)

/**
 * Section header used by every Settings section. Small bold caps title
 * + thin underline; common look across Interface / Network /
 * Experimental / Diagnostics / About.
 */
@Composable
internal fun SettingsSectionTitle(text: String) {
    Text(
        text,
        style      = MaterialTheme.typography.bodySmall,
        color      = NxTheme.colors.primary,
        fontWeight = FontWeight.Bold
    )
    Spacer(Modifier.height(4.dp))
    HorizontalDivider(color = NxTheme.colors.primary.copy(alpha = 0.3f))
    Spacer(Modifier.height(8.dp))
}

/**
 * Settings row with icon + title + description + switch. [enabled]
 * grays out the whole row (used by experimental sub-toggles when the
 * master is off).
 */
@Composable
internal fun SettingsRowWithDescription(
    title: String,
    description: String,
    icon: IconKey,
    iconTint: Color,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val alpha = if (enabled) 1f else 0.4f
    val style = LocalStyle.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(style.cardCorner))
            .background(settingsRowBackground())
            .padding(16.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Symbol(
                icon, null,
                tint     = iconTint.copy(alpha = alpha),
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    title,
                    color      = NxTheme.colors.textPrimary.copy(alpha = alpha),
                    fontWeight = FontWeight.Bold
                )
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = NxTheme.colors.textSecondary.copy(alpha = alpha)
                )
            }
        }
        NxSwitch(
            checked         = checked,
            enabled         = enabled,
            onCheckedChange = onCheckedChange,
        )
    }
}

@Composable
internal fun SettingsSwitchRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment     = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, color = NxTheme.colors.textPrimary)
        NxSwitch(
            checked         = checked,
            onCheckedChange = onCheckedChange,
        )
    }
}

/**
 * Full toggle card -- icon (tinted with [accent] when on) + title + description +
 * [NxSwitch], with a reactive [accent] wash on the whole row while checked. The rich
 * toggle treatment (the offline-mode reference), as opposed to the bare
 * [SettingsSwitchRow]. [accent] drives the active icon / wash / switch track.
 */
@Composable
internal fun SettingsToggleCard(
    icon: IconKey,
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    accent: Color = NxTheme.colors.primary,
) {
    val style = LocalStyle.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(style.cardCorner))
            .background(if (checked) accent.copy(alpha = 0.08f) else settingsRowBackground())
            .padding(16.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Symbol(
                icon, null,
                tint     = if (checked) accent else NxTheme.colors.textSecondary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column {
                Text(title, color = NxTheme.colors.textPrimary, fontWeight = FontWeight.Bold)
                Text(description, style = MaterialTheme.typography.bodySmall, color = NxTheme.colors.textSecondary)
            }
        }
        NxSwitch(checked = checked, onCheckedChange = onCheckedChange, accent = accent)
    }
}

// Static day/night colours -- the sun stays warm-orange and the moon cool-blue
// regardless of palette, so the dark-theme toggle reads as day/night at a glance.
// Orange over pale yellow: the sun has to stay legible over a light wallpaper.
private val SunOrange = Color(0xFFFF8C00)
private val MoonBlue  = Color(0xFF8AB4F8)

/**
 * Dark-theme toggle in the [SettingsToggleCard] shape but with a day/night identity:
 * sun (warm) when light, moon (cool) when dark. The icon, the row wash and the switch
 * track all take that FIXED colour, not the palette accent.
 */
@Composable
internal fun ThemeToggleCard(
    checked: Boolean,
    title: String,
    description: String,
    onCheckedChange: (Boolean) -> Unit,
) {
    val style = LocalStyle.current
    val tint = if (checked) MoonBlue else SunOrange
    // Flip the theme through the GNOME-style reveal when a host is present; the circle
    // grows out of the switch's own position. No host (or motion off) -> plain flip.
    val reveal = LocalThemeReveal.current
    val durationMs = style.animationDurationMs(550)
    var switchOrigin by remember { mutableStateOf(Offset.Zero) }
    val onToggle: (Boolean) -> Unit = { newValue ->
        if (reveal != null) reveal.reveal(switchOrigin, durationMs) { onCheckedChange(newValue) }
        else onCheckedChange(newValue)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(style.cardCorner))
            .background(settingsRowBackground())
            .padding(16.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            Symbol(
                if (checked) NxIcon.DarkMode else NxIcon.LightMode, null,
                tint     = tint,
                modifier = Modifier.size(24.dp),
            )
            Spacer(Modifier.width(16.dp))
            Column {
                Text(title, color = NxTheme.colors.textPrimary, fontWeight = FontWeight.Bold)
                Text(description, style = MaterialTheme.typography.bodySmall, color = NxTheme.colors.textSecondary)
            }
        }
        NxSwitch(
            checked         = checked,
            onCheckedChange = onToggle,
            accent          = tint,
            modifier        = Modifier.onGloballyPositioned { switchOrigin = it.boundsInWindow().center },
        )
    }
}

/**
 * Pair of pill-style buttons for picking [HomeView]. Active pill takes
 * the primary fill; inactive sits on surface. Designed to be extensible
 * past two options when more home variants land.
 */
@Composable
internal fun HomeViewPicker(
    current: HomeView,
    onChange: (HomeView) -> Unit,
    labelTitle: String,
    labelSub: String,
    classicLabel: String,
    libraryLabel: String,
    newLabel: String,
) {
    val style = LocalStyle.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(style.cardCorner))
            .background(settingsRowBackground())
            .padding(16.dp),
    ) {
        Text(labelTitle, color = NxTheme.colors.textPrimary, fontWeight = FontWeight.Bold)
        Text(
            labelSub,
            style = MaterialTheme.typography.bodySmall,
            color = NxTheme.colors.textSecondary,
        )
        Spacer(Modifier.height(12.dp))
        // FlowRow so the pills wrap to a second line on a narrow pane instead
        // of the longest label ("New (prototype)") shrinking and wrapping per
        // character.
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement   = Arrangement.spacedBy(8.dp),
        ) {
            VariantPill(
                label    = classicLabel,
                selected = current == HomeView.Classic,
                onClick  = { onChange(HomeView.Classic) },
            )
            VariantPill(
                label    = libraryLabel,
                selected = current == HomeView.LibraryFirst,
                onClick  = { onChange(HomeView.LibraryFirst) },
            )
            VariantPill(
                label    = newLabel,
                selected = current == HomeView.New,
                onClick  = { onChange(HomeView.New) },
            )
        }
    }
}

/**
 * Mirror of [HomeViewPicker] for the visual-style axis. Same pill UI,
 * different domain. Future variants slot in by adding rows here and
 * options to [UiStyle].
 */
@Composable
internal fun UiStylePicker(
    current: UiStyle,
    onChange: (UiStyle) -> Unit,
    title: String,
    sub: String,
    celestia: String,
    brut: String,
) {
    val style = LocalStyle.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(style.cardCorner))
            .background(settingsRowBackground())
            .padding(16.dp),
    ) {
        Text(title, color = NxTheme.colors.textPrimary, fontWeight = FontWeight.Bold)
        Text(sub, style = MaterialTheme.typography.bodySmall, color = NxTheme.colors.textSecondary)
        Spacer(Modifier.height(12.dp))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement   = Arrangement.spacedBy(8.dp),
        ) {
            VariantPill(label = celestia, selected = current == UiStyle.Celestia, onClick = { onChange(UiStyle.Celestia) })
            VariantPill(label = brut,     selected = current == UiStyle.Brut,     onClick = { onChange(UiStyle.Brut) })
        }
    }
}

@Composable
private fun VariantPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) NxTheme.colors.primary.copy(alpha = 0.18f)
             else glassSurfaceAlpha(0.4f)
    val fg = if (selected) NxTheme.colors.primary else NxTheme.colors.textSecondary
    val style = LocalStyle.current
    Row(
        Modifier
            .clip(RoundedCornerShape(style.buttonCorner))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            color      = fg,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            maxLines   = 1,
            softWrap   = false,
        )
    }
}
