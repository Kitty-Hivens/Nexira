package hivens.ui.screens.library.content

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mikepenz.markdown.m3.Markdown
import hivens.core.api.dto.smrt.SmrtModEntry
import hivens.core.api.dto.smrt.SmrtRequirement
import hivens.launcher.smrt.DepGraph
import hivens.ui.customization.glassSurfaceAlpha
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.theme.NxTheme
import java.awt.Desktop
import java.net.URI

/**
 * One row for a mod in the Content tab. Collapsed: icon + name + version
 * + source badge. Click to expand into description (markdown), license,
 * URL, and the mod's direct dependencies pulled from [graph].
 *
 * The [emphasis] mode controls visual weight: an [Emphasis.Primary]
 * row is bold and full-colour (the active member of a role group or
 * a stand-alone mod); [Emphasis.Alternative] is greyed + strikethrough
 * (a non-active member of a role group).
 */
@Composable
fun ModRowPanel(
    mod: SmrtModEntry,
    graph: DepGraph,
    emphasis: Emphasis = Emphasis.Primary,
    toggle: ModToggle? = null,
    modifier: Modifier = Modifier,
) {
    var expanded by remember(mod.filename) { mutableStateOf(false) }

    val rowAlpha = if (emphasis == Emphasis.Alternative) 0.55f else 1f
    val titleDecoration =
        if (emphasis == Emphasis.Alternative) TextDecoration.LineThrough else TextDecoration.None

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(glassSurfaceAlpha(0.45f * rowAlpha))
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(
            verticalAlignment     = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier              = Modifier.fillMaxWidth(),
        ) {
            // Checkbox left of the avatar: optional mods toggle on/off here;
            // required mods show it checked + locked so the column stays aligned
            // and "required = always installed" reads at a glance.
            //
            // The expand-click below lives on the rest of the row only -- earlier
            // it sat on the surrounding Column and ate the checkbox click before
            // the Checkbox saw it, so toggles appeared to do nothing.
            if (toggle != null) {
                Checkbox(
                    checked         = toggle.checked,
                    onCheckedChange = if (toggle.locked) null else { enable -> toggle.onToggle(mod.filename, enable) },
                    enabled         = !toggle.locked,
                    modifier        = Modifier.size(24.dp),
                )
            }

            Row(
                modifier              = Modifier
                    .weight(1f)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { expanded = !expanded },
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ModIconImage(mod = mod, size = 28.dp)

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text           = mod.display?.name ?: mod.filename.removeSuffix(".jar"),
                        style          = MaterialTheme.typography.bodyMedium,
                        color          = NxTheme.colors.textPrimary.copy(alpha = rowAlpha),
                        fontWeight     = if (emphasis == Emphasis.Primary) FontWeight.SemiBold else FontWeight.Normal,
                        textDecoration = titleDecoration,
                        maxLines       = 1,
                        overflow       = TextOverflow.Ellipsis,
                    )
                    mod.display?.category?.takeIf { it.isNotBlank() }?.let { cat ->
                        Text(
                            text  = cat,
                            style = MaterialTheme.typography.labelSmall,
                            color = NxTheme.colors.textSecondary.copy(alpha = rowAlpha),
                        )
                    }
                }

                SourceBadge(mod.source)

                Symbol(icon = if (expanded) NxIcon.ExpandLess else NxIcon.ExpandMore,
                    contentDescription = null,
                    tint               = NxTheme.colors.textSecondary,
                    modifier           = Modifier.size(20.dp),
                )
            }
        }

        if (expanded) {
            Spacer(Modifier.height(12.dp))
            ExpandedDetails(mod = mod, graph = graph)
        }
    }
}

enum class Emphasis { Primary, Alternative }

/**
 * Drives the leading checkbox on a mod row. [locked] (required mods) renders a
 * checked, disabled box; otherwise [checked] is the optional mod's current state
 * and [onToggle] flips it. Null on a [ModRowPanel] means no checkbox (e.g. a
 * role-group alternative, where selection is its own UI).
 *
 * [onToggle] takes the mod's filename so a single hoisted callback can serve
 * every row -- a per-row closure would change ModToggle's identity each
 * recompose and stop ModRowPanel from skipping.
 */
data class ModToggle(
    val checked: Boolean,
    val locked: Boolean,
    val onToggle: (filename: String, enable: Boolean) -> Unit,
)

@Composable
private fun ExpandedDetails(mod: SmrtModEntry, graph: DepGraph) {
    val s = LocalStrings.current
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(start = 42.dp, end = 6.dp)) {
        // Description: markdown when present, plain fallback line otherwise.
        val description = mod.display?.description?.takeIf { it.isNotBlank() }
        if (description != null) {
            Markdown(content = description)
        } else {
            Text(
                text  = s.contentTabModNoDescription,
                style = MaterialTheme.typography.bodySmall,
                color = NxTheme.colors.textSecondary,
            )
        }

        // License + URL chip row.
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            mod.display?.license?.takeIf { it.isNotBlank() }?.let { lic ->
                MetaChip(text = s.contentTabModLicensePrefix(lic))
            }
            mod.display?.url?.takeIf { it.isNotBlank() }?.let { url ->
                LinkChip(text = s.contentTabModUrlLabel, url = url)
            }
            Text(
                text  = s.contentTabModSizeLabel(mod.sizeBytes / 1024L),
                style = MaterialTheme.typography.labelSmall,
                color = NxTheme.colors.textSecondary,
            )
        }

        // Dependencies subsection -- only rendered when the mod has at
        // least one declared requirement. Mirror today does not author
        // `display.requires`, so emitting "Зависимости (0)" on every
        // expanded mod was pure noise; the row reappears automatically
        // once the mirror starts populating the field.
        if (graph.edges.any { it.from == mod.filename } ||
            graph.missingRequirements.any { it.from == mod.filename }) {
            DependenciesSubsection(mod = mod, graph = graph)
        }
    }
}

@Composable
private fun DependenciesSubsection(mod: SmrtModEntry, graph: DepGraph) {
    val s = LocalStrings.current
    val depEdges  = graph.edges.filter { it.from == mod.filename }
    val missing   = graph.missingRequirements.filter { it.from == mod.filename }
    val empty     = depEdges.isEmpty() && missing.isEmpty()

    var open by remember(mod.filename) { mutableStateOf(false) }

    Row(
        modifier              = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.small)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = !empty) { open = !open }
            .padding(vertical = 4.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text  = s.contentTabModDependencies(depEdges.size + missing.size),
            style = MaterialTheme.typography.labelMedium,
            color = NxTheme.colors.textPrimary,
        )
        if (!empty) {
            Symbol(icon = if (open) NxIcon.ExpandLess else NxIcon.ExpandMore,
                contentDescription = null,
                tint               = NxTheme.colors.textSecondary,
                modifier           = Modifier.size(16.dp),
            )
        }
        if (missing.isNotEmpty()) {
            Spacer(Modifier.width(4.dp))
            Symbol(NxIcon.Warning, contentDescription = null, tint = NxTheme.colors.error, modifier = Modifier.size(14.dp))
            Text(
                text  = s.contentTabModMissingCount(missing.size),
                style = MaterialTheme.typography.labelSmall,
                color = NxTheme.colors.error,
            )
        }
    }

    if (open) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(start = 12.dp, top = 4.dp)) {
            depEdges.forEach { edge ->
                DependencyRow(
                    filename     = edge.to,
                    versionRange = edge.versionRange,
                    optional     = edge.optional,
                    missing      = false,
                )
            }
            missing.forEach { miss ->
                DependencyRow(
                    filename     = miss.requiresFilename,
                    versionRange = null,
                    optional     = false,
                    missing      = true,
                )
            }
        }
    }
}

@Composable
private fun DependencyRow(filename: String, versionRange: String?, optional: Boolean, missing: Boolean) {
    val s = LocalStrings.current
    Row(
        modifier              = Modifier.fillMaxWidth(),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            modifier         = Modifier
                .size(6.dp)
                .background(if (missing) NxTheme.colors.error else NxTheme.colors.primary.copy(alpha = 0.7f)),
        )
        Text(
            text  = filename,
            style = MaterialTheme.typography.bodySmall,
            color = if (missing) NxTheme.colors.error else NxTheme.colors.textPrimary,
        )
        if (versionRange != null) MetaChip(text = versionRange)
        if (optional)             MetaChip(text = s.contentTabDepOptional)
        if (missing)              MetaChip(text = s.contentTabDepMissing, error = true)
    }
}

@Composable
private fun MetaChip(text: String, error: Boolean = false) {
    AssistChip(
        onClick = {},
        enabled = false,
        shape   = MaterialTheme.shapes.extraSmall,
        label   = {
            Text(
                text  = text,
                style = MaterialTheme.typography.labelSmall,
                color = if (error) NxTheme.colors.error else NxTheme.colors.textSecondary,
            )
        },
        colors  = AssistChipDefaults.assistChipColors(
            disabledContainerColor = if (error) NxTheme.colors.error.copy(alpha = 0.15f)
                                     else NxTheme.colors.outline.copy(alpha = 0.2f),
            disabledLabelColor     = if (error) NxTheme.colors.error else NxTheme.colors.textSecondary,
        ),
        border  = null,
    )
}

@Composable
private fun LinkChip(text: String, url: String) {
    Row(
        modifier              = Modifier
            .clip(MaterialTheme.shapes.extraSmall)
            .background(NxTheme.colors.primary.copy(alpha = 0.2f))
            .clickable { runCatching { Desktop.getDesktop().browse(URI(url)) } }
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Symbol(NxIcon.OpenInNew, contentDescription = null, tint = NxTheme.colors.primary, modifier = Modifier.size(12.dp))
        Text(
            text  = text,
            style = MaterialTheme.typography.labelSmall,
            color = NxTheme.colors.primary,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

