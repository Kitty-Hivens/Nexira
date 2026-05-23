package hivens.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.core.data.HomeView
import hivens.core.data.UiStyle
import hivens.ui.theme.CelestiaTheme
import hivens.ui.theme.LocalStyle

/**
 * Background color for Settings row / picker / panel containers.
 *
 * Always returns the subtle Glass tint (background.copy alpha 0.4),
 * regardless of the active style's cardSurface preference. User
 * decided 2026-05-23: "это всё таки настройки пользователя" -- the
 * Settings page is where the user expresses preferences, so the
 * panel style here should stay neutral and not be re-painted as
 * opaque just because the global style toggle says Flat. cardSurface
 * still drives GlassCard / content cards elsewhere; this single
 * surface (Settings) opts out.
 */
@Composable
internal fun settingsRowBackground(): androidx.compose.ui.graphics.Color =
    CelestiaTheme.colors.background.copy(alpha = 0.4f)

/**
 * Section header used by every Settings section. Small bold caps title
 * + thin underline; common look across Interface / Network /
 * Experimental / Diagnostics / About.
 */
@Composable
internal fun SettingsSectionTitle(text: String) {
    Text(
        text.uppercase(),
        style      = MaterialTheme.typography.bodySmall,
        color      = CelestiaTheme.colors.primary,
        fontWeight = FontWeight.Bold
    )
    Spacer(Modifier.height(4.dp))
    HorizontalDivider(color = CelestiaTheme.colors.primary.copy(alpha = 0.3f))
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
    icon: ImageVector,
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
            Icon(
                icon, null,
                tint     = iconTint.copy(alpha = alpha),
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    title,
                    color      = CelestiaTheme.colors.textPrimary.copy(alpha = alpha),
                    fontWeight = FontWeight.Bold
                )
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = CelestiaTheme.colors.textSecondary.copy(alpha = alpha)
                )
            }
        }
        Switch(
            checked         = checked,
            enabled         = enabled,
            onCheckedChange = onCheckedChange,
            colors          = SwitchDefaults.colors(
                checkedThumbColor = CelestiaTheme.colors.primary,
                checkedTrackColor = CelestiaTheme.colors.primary.copy(alpha = 0.5f)
            )
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
        Text(title, style = MaterialTheme.typography.bodyLarge, color = CelestiaTheme.colors.textPrimary)
        Switch(
            checked         = checked,
            onCheckedChange = onCheckedChange,
            colors          = SwitchDefaults.colors(
                checkedThumbColor  = CelestiaTheme.colors.primary,
                checkedTrackColor  = CelestiaTheme.colors.primary.copy(alpha = 0.5f)
            )
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
) {
    val style = LocalStyle.current
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(style.cardCorner))
            .background(settingsRowBackground())
            .padding(16.dp),
    ) {
        Text(labelTitle, color = CelestiaTheme.colors.textPrimary, fontWeight = FontWeight.Bold)
        Text(
            labelSub,
            style = MaterialTheme.typography.bodySmall,
            color = CelestiaTheme.colors.textSecondary,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        Text(title, color = CelestiaTheme.colors.textPrimary, fontWeight = FontWeight.Bold)
        Text(sub, style = MaterialTheme.typography.bodySmall, color = CelestiaTheme.colors.textSecondary)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            VariantPill(label = celestia, selected = current == UiStyle.Celestia, onClick = { onChange(UiStyle.Celestia) })
            VariantPill(label = brut,     selected = current == UiStyle.Brut,     onClick = { onChange(UiStyle.Brut) })
        }
    }
}

@Composable
private fun VariantPill(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) CelestiaTheme.colors.primary.copy(alpha = 0.18f)
             else CelestiaTheme.colors.surface.copy(alpha = 0.4f)
    val fg = if (selected) CelestiaTheme.colors.primary else CelestiaTheme.colors.textSecondary
    val style = LocalStyle.current
    Row(
        Modifier
            .clip(RoundedCornerShape(style.buttonCorner))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = fg, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}
