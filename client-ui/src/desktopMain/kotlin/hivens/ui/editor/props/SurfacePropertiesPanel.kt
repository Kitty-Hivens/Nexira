package hivens.ui.editor.props

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.ui.customization.CustomizationSettings
import hivens.ui.customization.NavSelectionStyle
import hivens.ui.customization.glassSurfaceAlpha
import hivens.ui.i18n.AppStrings
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.puppet.PuppetClick
import hivens.ui.puppet.PuppetToggle
import hivens.ui.screens.settings.settingsRowBackground
import hivens.ui.theme.CelestiaTheme
import hivens.ui.theme.LocalStyle
import hivens.ui.widgets.customization.HexField

// Right-edge settings panel for a whole SURFACE (region), distinct from the
// per-widget WidgetPropPanel. Opened from the editor pill's settings affordance
// when the selected surface exposes surface-level settings -- currently only the
// left nav rail. Mirrors WidgetPropPanel's chrome (320dp, solid surface, slide
// in from the right) and writes through CustomizationSettings -- the same store
// the rail reads at runtime -- so changes are live and persist beyond edit mode.
// Tied to the region: it lives next to "Подложка", not in the global Appearance
// settings, so it cannot orphan when the rail's widgets are removed.
@Composable
fun SurfacePropertiesPanel(
    visible: Boolean,
    title: String,
    customization: CustomizationSettings,
    onCustomizationChanged: (CustomizationSettings) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible  = visible,
        enter    = fadeIn(spring()) + slideInHorizontally(spring(stiffness = Spring.StiffnessMediumLow)) { it },
        exit     = fadeOut(spring()) + slideOutHorizontally(spring(stiffness = Spring.StiffnessMediumLow)) { it },
        modifier = modifier,
    ) {
        val s = LocalStrings.current
        // Draggable dock: the header drags this offset (session-scoped), like the
        // widget palette, so the panel can be pulled off the right edge.
        var offset by remember { mutableStateOf(Offset.Zero) }
        Column(
            modifier = Modifier
                .graphicsLayer { translationX = offset.x; translationY = offset.y }
                .width(320.dp)
                .fillMaxHeight()
                .padding(top = 64.dp, bottom = 96.dp, end = 16.dp)
                .shadow(elevation = 18.dp, shape = RoundedCornerShape(14.dp))
                .clip(RoundedCornerShape(14.dp))
                // Solid surface, no glass: a settings panel must stay readable and
                // not composite with the layers it floats over.
                .background(CelestiaTheme.colors.surface),
        ) {
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier              = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            offset += drag
                        }
                    }
                    .padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Symbol(icon = NxIcon.ViewSidebar,
                        contentDescription = null,
                        tint               = CelestiaTheme.colors.primary,
                        modifier           = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text       = title,
                        style      = MaterialTheme.typography.titleSmall,
                        color      = CelestiaTheme.colors.textPrimary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Symbol(icon = NxIcon.Close,
                        contentDescription = s.editorClose,
                        tint               = CelestiaTheme.colors.textSecondary,
                        modifier           = Modifier.size(16.dp),
                    )
                }
            }

            Column(
                modifier            = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                NavSelectionControl(customization = customization, onChange = onCustomizationChanged)
            }
        }
    }
}

// ─── Nav selection style control ──────────────────────────────────────────────
// Moved here from the global Appearance settings: the active-item highlight is a
// property of the LEFT RAIL, so it belongs in the rail's own surface settings.
// Reads/writes CustomizationSettings, which the rail's NavSlot reads at runtime.

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NavSelectionControl(
    customization: CustomizationSettings,
    onChange: (CustomizationSettings) -> Unit,
) {
    val s = LocalStrings.current
    val style = LocalStyle.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(style.cardCorner))
            .background(settingsRowBackground())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column {
            Text(s.navSelectionTitle, color = CelestiaTheme.colors.textPrimary, fontWeight = FontWeight.Bold)
            Text(
                s.navSelectionSub,
                style = MaterialTheme.typography.bodySmall,
                color = CelestiaTheme.colors.textSecondary,
            )
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement   = Arrangement.spacedBy(6.dp),
        ) {
            NavSelectionStyle.entries.forEach { variant ->
                val selected = customization.navSelectionStyle == variant
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(style.buttonCorner))
                        .background(
                            if (selected) CelestiaTheme.colors.primary.copy(alpha = 0.18f)
                            else glassSurfaceAlpha(0.4f),
                        )
                        .border(
                            width = 1.dp,
                            color = if (selected) CelestiaTheme.colors.primary
                            else CelestiaTheme.colors.outline.copy(alpha = 0.25f),
                            shape = RoundedCornerShape(style.buttonCorner),
                        )
                        .clickable { onChange(customization.copy(navSelectionStyle = variant)) }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    Text(
                        text       = navSelectionStyleLabel(variant, s),
                        style      = MaterialTheme.typography.bodySmall,
                        color      = if (selected) CelestiaTheme.colors.primary else CelestiaTheme.colors.textSecondary,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
                PuppetClick("settings.navSelection.${variant.name}") {
                    onChange(customization.copy(navSelectionStyle = variant))
                }
            }
        }

        CompactSwitchRow(
            title           = s.navSelectionOutlineIcons,
            checked         = customization.navSelectionOutlineIcons,
            onCheckedChange = { onChange(customization.copy(navSelectionOutlineIcons = it)) },
        )
        PuppetToggle("settings.navSelection.outlineIcons", customization.navSelectionOutlineIcons) {
            onChange(customization.copy(navSelectionOutlineIcons = it))
        }

        CompactSwitchRow(
            title           = s.navHoverHighlight,
            checked         = customization.navHoverHighlight,
            onCheckedChange = { onChange(customization.copy(navHoverHighlight = it)) },
        )
        PuppetToggle("settings.navSelection.hoverHighlight", customization.navHoverHighlight) {
            onChange(customization.copy(navHoverHighlight = it))
        }

        // Label above, field + clear below: the 320dp panel is too narrow for a
        // label + hex field + worded button on one line (the button wrapped and
        // squeezed the field). The clear collapses to a compact icon.
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text  = s.navSelectionAccent,
                style = MaterialTheme.typography.bodySmall,
                color = CelestiaTheme.colors.textSecondary,
            )
            Row(
                modifier              = Modifier.fillMaxWidth(),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                HexField(
                    initialHex   = customization.navSelectionAccent ?: "",
                    invalidLabel = s.customizationHexInvalid,
                    onValidHex   = { onChange(customization.copy(navSelectionAccent = it)) },
                    modifier     = Modifier.weight(1f),
                    rgbOnly      = true,
                )
                if (customization.navSelectionAccent != null) {
                    OutlinedButton(
                        onClick        = { onChange(customization.copy(navSelectionAccent = null)) },
                        shape          = MaterialTheme.shapes.small,
                        contentPadding = PaddingValues(8.dp),
                    ) {
                        Symbol(icon = NxIcon.Close,
                            contentDescription = s.customizationAccentClear,
                            modifier           = Modifier.size(14.dp),
                        )
                    }
                }
            }
        }
    }
}

private fun navSelectionStyleLabel(variant: NavSelectionStyle, s: AppStrings): String =
    when (variant) {
        NavSelectionStyle.Pill    -> s.navStylePill
        NavSelectionStyle.Square  -> s.navStyleSquare
        NavSelectionStyle.Circle  -> s.navStyleCircle
        NavSelectionStyle.LeftBar -> s.navStyleBar
        NavSelectionStyle.Dot     -> s.navStyleDot
        NavSelectionStyle.None    -> s.navStyleNone
    }

// Smaller than the settings-screen SettingsSwitchRow (bodyLarge): the 320dp
// surface panel cramps long toggle names, so the label drops to bodySmall and
// takes the row's remaining width with the Switch pinned at the end.
@Composable
private fun CompactSwitchRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier              = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment     = Alignment.CenterVertically,
    ) {
        Text(
            text     = title,
            style    = MaterialTheme.typography.bodySmall,
            color    = CelestiaTheme.colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        Switch(
            checked         = checked,
            onCheckedChange = onCheckedChange,
            colors          = SwitchDefaults.colors(
                checkedThumbColor = CelestiaTheme.colors.primary,
                checkedTrackColor = CelestiaTheme.colors.primary.copy(alpha = 0.5f),
            ),
        )
    }
}
