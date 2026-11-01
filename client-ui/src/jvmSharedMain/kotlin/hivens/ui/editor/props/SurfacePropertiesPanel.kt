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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import hivens.ui.i18n.AppStrings
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.IconKey
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.puppet.PuppetClick
import hivens.ui.puppet.PuppetToggle
import hivens.ui.nx.NxRow
import hivens.ui.nx.NxSwitch
import hivens.ui.surface.NxSurface
import hivens.ui.editor.rememberDockOffset
import hivens.ui.widgets.customization.HexField
import hivens.ui.theme.NxInk
import hivens.ui.theme.NxColor
import hivens.ui.surface.SurfaceKind
import hivens.ui.theme.OnFill

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
    EditorSidePanel(visible = visible, title = title, icon = NxIcon.ViewSidebar, onDismiss = onDismiss, modifier = modifier) {
        NavSelectionControl(customization = customization, onChange = onCustomizationChanged)
    }
}

/**
 * The editor's right-edge panel frame: a solid popup 320 wide, sliding in from the
 * edge, with a header that drags it off the edge and closes it.
 *
 * One frame for every panel about a whole thing rather than one widget, so a
 * region's settings and a made screen's settings sit in the same place and move
 * the same way.
 */
@Composable
internal fun EditorSidePanel(
    visible: Boolean,
    title: String,
    icon: IconKey,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
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
        val offset = rememberDockOffset()
        NxSurface(
            // A popup: solid and above everything, so a settings panel stays
            // readable and does not composite with the layers it floats over.
            kind     = SurfaceKind.Popup,
            shape    = MaterialTheme.shapes.large,
            shadowDp = PANEL_SHADOW_DP,
            modifier = Modifier
                .graphicsLayer { translationX = offset.value.x; translationY = offset.value.y }
                .width(320.dp)
                .fillMaxHeight()
                .padding(top = 64.dp, bottom = 96.dp, end = 16.dp),
        ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier              = Modifier
                    .fillMaxWidth()
                    .pointerInput(Unit) {
                        // No slop: a header is a handle. detectDragGestures holds the
                        // first few pixels back before it reports anything, which on a
                        // short strip reads as the panel refusing to be grabbed. The
                        // editor's own widget drag starts on the press for this reason.
                        // requireUnconsumed yields to the close button sitting in here.
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = true)
                            down.consume()
                            drag(down.id) { change ->
                                // Delta first: positionChange() reports Offset.Zero once
                                // the change is consumed, so claiming it before reading it
                                // moves the panel by nothing.
                                offset.drag(change.positionChange())
                                change.consume()
                            }
                        }
                    }
                    .padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Symbol(icon = icon,
                        contentDescription = null,
                        tint               = NxColor.lead(),
                        modifier           = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text       = title,
                        style      = MaterialTheme.typography.titleSmall,
                        color      = NxInk.main,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Symbol(icon = NxIcon.Close,
                        contentDescription = s.editorClose,
                        tint               = NxInk.quiet,
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
                content             = content,
            )
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

    // A group of choices set into the panel, so a field: one step back from it.
    NxSurface(SurfaceKind.Field, Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column {
            Text(s.navSelectionTitle, color = NxInk.main, fontWeight = FontWeight.Bold)
            Text(
                s.navSelectionSub,
                style = MaterialTheme.typography.bodySmall,
                color = NxInk.quiet,
            )
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement   = Arrangement.spacedBy(6.dp),
        ) {
            NavSelectionStyle.entries.forEach { variant ->
                val selected = customization.navSelectionStyle == variant
                val fill = if (selected) NxColor.wash(NxColor.lead(), 0.18f) else NxColor.wash(NxInk.quiet, 0.08f)
                Box(
                    modifier = Modifier
                        .clip(MaterialTheme.shapes.small)
                        .background(fill)
                        .border(
                            width = 1.dp,
                            color = if (selected) NxColor.lead() else NxInk.line,
                            shape = MaterialTheme.shapes.small,
                        )
                        .clickable { onChange(customization.withNavSelectionStyle(variant)) }
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    OnFill(fill) {
                        Text(
                            text       = navSelectionStyleLabel(variant, s),
                            style      = MaterialTheme.typography.bodySmall,
                            color      = if (selected) NxColor.lead(text = true) else NxInk.quiet,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
                PuppetClick("settings.navSelection.${variant.name}") {
                    onChange(customization.withNavSelectionStyle(variant))
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
                color = NxInk.quiet,
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

// The library's row in its panel form: smaller than a settings-screen row, because
// a 320dp panel cramps long toggle names. This was the third hand-rolled answer to
// "a label with a control beside it" in one feature. The row it replaces differed
// from the other two only in which of them it happened to be written after.
//
// No pinned label column, unlike the prop panel: this block is a list of toggles
// rather than a form of unlike controls, and a switch against the far edge is what
// a list of them reads as.
@Composable
private fun CompactSwitchRow(title: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    NxRow(title = title, compact = true) {
        NxSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
