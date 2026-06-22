package hivens.ui.widgets.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import hivens.ui.Screen
import hivens.ui.chrome.rememberCrumbLabel
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.theme.CelestiaTheme
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance

/**
 * Clickable breadcrumb of the current location (Modrinth-style). Back button +
 * the root-to-current trail from [ShellContext]; non-last segments jump back via
 * onPopTo. Labels resolve through [rememberCrumbLabel] (pack/server names included).
 */
@Widget(id = "appshell.topbar.breadcrumb", displayName = "widget.appshell.topbar.breadcrumb")
@Composable
fun TopBarBreadcrumbWidget(instance: WidgetInstance) {
    val ctx = LocalShellContext.current
    val s = LocalStrings.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Back / forward are always present so the breadcrumb never shifts; each
        // greys out and stops responding when there is nowhere to go that way.
        NavArrow(enabled = ctx.canGoBack, mirrored = false, contentDescription = s.navBack, onClick = ctx.onBack)
        // Forward: the back arrow mirrored, so the pair matches exactly.
        NavArrow(enabled = ctx.canGoForward, mirrored = true, contentDescription = s.navForward, onClick = ctx.onForward)
        val trail = ctx.trail
        trail.forEachIndexed { i, screen ->
            val isLast = i == trail.lastIndex
            CrumbSegment(
                label = rememberCrumbLabel(screen),
                isLast = isLast,
                onClick = { if (!isLast) ctx.onPopTo(screen) },
            )
            if (!isLast) {
                Symbol(
                    icon = NxIcon.ChevronRight,
                    contentDescription = null,
                    tint = CelestiaTheme.colors.textSecondary,
                    size = 16.dp,
                )
            }
        }
    }
}

/** Compact back / forward control: a 32dp circular tap target (tighter than an
 *  IconButton's 48dp minimum, so the pair sits close together), greyed and inert
 *  when disabled. */
@Composable
private fun NavArrow(
    enabled: Boolean,
    mirrored: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .size(32.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Symbol(
            icon = NxIcon.ArrowBack,
            contentDescription = contentDescription,
            modifier = if (mirrored) Modifier.graphicsLayer { scaleX = -1f } else Modifier,
            tint = CelestiaTheme.colors.textSecondary.copy(alpha = if (enabled) 1f else 0.3f),
            size = 18.dp,
        )
    }
}

@Composable
private fun CrumbSegment(label: String, isLast: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = if (isLast) CelestiaTheme.colors.textPrimary else CelestiaTheme.colors.textSecondary,
        fontWeight = if (isLast) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 1,
        modifier = (if (isLast) Modifier else Modifier.clickable(onClick = onClick))
            .padding(horizontal = 4.dp, vertical = 4.dp),
    )
}
