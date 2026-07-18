package hivens.ui.screens.detail.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.ui.components.NavItemRowContent
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.nx.NxIconButton
import hivens.ui.puppet.PuppetClick
import hivens.ui.puppet.PuppetScreen
import hivens.ui.surface.NxSurface
import hivens.ui.surface.NxSurfaceLevel
import hivens.ui.theme.LocalStyle
import hivens.ui.theme.NxTheme
import java.nio.file.Path

/**
 * The floating pack-settings window: a scrimmed overlay hosting a section rail
 * on the left and the selected section's controls on the right -- the global
 * Settings "by sections" grammar, but as a transient panel over the pack detail
 * rather than a nav route. Replaces the old memory-only settings modal.
 *
 * A pack instance is the unit of edit: sections mutate it and flow the result
 * back through [onInstanceChange] (which persists and refreshes the hero), so
 * there is no separate form-state blob -- each control is a `copy` + `put`.
 */
@Composable
fun PackSettingsWindow(
    pack: PackInstance,
    instanceDir: Path,
    onInstanceChange: (PackInstance) -> Unit,
    onDismiss: () -> Unit,
) {
    val s = LocalStrings.current
    val style = LocalStyle.current
    val colors = NxTheme.colors
    PuppetScreen("PackSettings.${pack.id}")

    val isMirror = pack.packRef.origin == PackOrigin.Mirror
    val categories = remember(isMirror) {
        PackSettingsCategory.entries.filter { isMirror || !it.mirrorOnly }
    }
    var selected by remember(pack.id) { mutableStateOf(PackSettingsCategory.General) }
    // A detach mid-session drops the Version section; fall back so the pane never
    // dispatches a category the rail no longer shows.
    if (selected !in categories) selected = PackSettingsCategory.General

    // Scrim: click outside dismisses; the card swallows clicks so a stray tap
    // inside does not close the window.
    val scrim = remember { MutableInteractionSource() }
    val card = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.55f))
            .clickable(scrim, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        NxSurface(
            level = NxSurfaceLevel.Raised,
            // Opaque, not frosted: over the scrim a glass alpha reads as a
            // see-through window. A settings surface is a solid plane.
            glass = false,
            modifier = Modifier
                .widthIn(max = 1320.dp)
                .heightIn(max = 940.dp)
                .fillMaxWidth(0.88f)
                .fillMaxHeight(0.90f)
                .clip(RoundedCornerShape(style.cardCorner))
                .clickable(card, indication = null, onClick = {}),
        ) {
            Column(Modifier.fillMaxSize()) {
                // Header: identity + close. A floating window carries its own title
                // (the nav breadcrumb owns the global Settings title, but this panel
                // has no route identity).
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            pack.displayName,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = colors.textPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            s.packSettingsTitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.textSecondary,
                        )
                    }
                    PuppetClick("packSettings.close") { onDismiss() }
                    NxIconButton(
                        icon = NxIcon.Close,
                        contentDescription = s.packSettingsClose,
                        onClick = onDismiss,
                    )
                }

                Row(Modifier.fillMaxSize().padding(start = 12.dp, end = 16.dp, bottom = 16.dp)) {
                    // ── Rail ──────────────────────────────────────────────
                    Column(
                        modifier = Modifier.width(196.dp).fillMaxHeight().padding(end = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        categories.forEach { category ->
                            val isSelected = category == selected
                            PuppetClick("packSettings.category.${category.name}") { selected = category }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(style.cardCorner))
                                    .background(
                                        if (isSelected) colors.primary.copy(alpha = 0.18f)
                                        else Color.Transparent,
                                    )
                                    .clickable { selected = category }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                NavItemRowContent(
                                    icon = category.icon,
                                    label = category.label(s),
                                    isSelected = isSelected,
                                )
                            }
                        }
                    }

                    // ── Pane ──────────────────────────────────────────────
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        when (selected) {
                            PackSettingsCategory.General ->
                                PackGeneralSection(pack, onInstanceChange)
                            PackSettingsCategory.Runtime ->
                                PackRuntimeSection(pack, instanceDir, onInstanceChange)
                            PackSettingsCategory.Version ->
                                PackVersionSection(pack, onInstanceChange)
                            PackSettingsCategory.Content ->
                                PackContentSection(pack, onInstanceChange)
                            PackSettingsCategory.Data ->
                                PackDataSection(pack, instanceDir, onInstanceChange, onDismiss)
                        }
                    }
                }
            }
        }
    }
}
