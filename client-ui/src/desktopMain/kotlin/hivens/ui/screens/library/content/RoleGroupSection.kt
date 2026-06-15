package hivens.ui.screens.library.content

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.launcher.smrt.DepGraph
import hivens.launcher.smrt.ModRoleGroup
import hivens.ui.i18n.AppStrings
import hivens.ui.i18n.LocalStrings
import hivens.ui.theme.CelestiaTheme

/**
 * Renders one role group (Recipe viewer / Minimap / Waila / ...).
 * First member is rendered as the [Emphasis.Primary] row (bold, full
 * colour). Remaining members render as [Emphasis.Alternative] (greyed,
 * strikethrough) -- they are also on disk, just not the picked option.
 *
 * Switching the picked option is out of scope for the read-only first
 * pass; it would mean physically swapping mod jars and re-running
 * dep resolution. Today the Content tab is informational.
 */
@Composable
fun RoleGroupSection(
    group: ModRoleGroup,
    graph: DepGraph,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    val primary = group.members.firstOrNull() ?: return
    val alternatives = group.members.drop(1)

    Column(
        modifier            = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(CelestiaTheme.colors.primary.copy(alpha = 0.05f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text       = roleLabel(group.role, s),
                style      = MaterialTheme.typography.labelLarge,
                color      = CelestiaTheme.colors.primary,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(0.dp))
            Text(
                text  = s.contentTabRoleAltCount(alternatives.size),
                style = MaterialTheme.typography.labelSmall,
                color = CelestiaTheme.colors.textSecondary,
            )
        }

        ModRowPanel(mod = primary, graph = graph, emphasis = Emphasis.Primary)

        if (alternatives.isNotEmpty()) {
            Text(
                text  = s.contentTabRoleAlternativesHeader,
                style = MaterialTheme.typography.labelSmall,
                color = CelestiaTheme.colors.textSecondary,
                modifier = Modifier.padding(top = 4.dp),
            )
            alternatives.forEach { alt ->
                ModRowPanel(mod = alt, graph = graph, emphasis = Emphasis.Alternative)
            }
        }
    }
}

/**
 * Maps the lowercase role key from the manifest to a localised label
 * when known; otherwise falls back to a Title-Cased version of the
 * raw key. Mirror authors can use any key they want; the launcher
 * pretty-prints only the ones we've consciously localised.
 */
@Composable
private fun roleLabel(role: String, s: AppStrings): String = when (role) {
    "recipe_viewer" -> s.contentTabRoleRecipeViewer
    "minimap"       -> s.contentTabRoleMinimap
    "waila", "block_info" -> s.contentTabRoleBlockInfo
    "optimisation", "performance" -> s.contentTabRolePerformance
    "inventory_search"            -> s.contentTabRoleInventorySearch
    else            -> role.replace('_', ' ').replaceFirstChar { it.uppercaseChar() }
}
