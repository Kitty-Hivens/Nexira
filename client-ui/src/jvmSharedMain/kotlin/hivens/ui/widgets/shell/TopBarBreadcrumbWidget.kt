package hivens.ui.widgets.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import hivens.ui.chrome.rememberCrumbLabel
import hivens.ui.i18n.LocalStrings
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.widgets.Commands
import hivens.ui.widgets.Sources
import hivens.widget.api.rememberScopedAction
import hivens.widget.api.rememberScopedCommand
import hivens.widget.api.rememberScopedSource
import hivens.widget.api.valueOrNull
import hivens.widget.model.Widget
import hivens.ui.theme.NxInk

/**
 * Clickable breadcrumb of the current location (Modrinth-style). Back button +
 * the root-to-current trail of the composition's history; non-last segments jump
 * back to themselves. Labels resolve through [rememberCrumbLabel] (pack/server
 * names included).
 */
@Widget(
    id = "appshell.topbar.breadcrumb",
    displayName = "widget.appshell.topbar.breadcrumb",
    needs = ["nav.state", "nav.back", "nav.forward", "nav.popTo"],
)
@Composable
fun TopBarBreadcrumbWidget() {
    val nav = rememberScopedSource(Sources.Navigation).valueOrNull() ?: return
    val back = rememberScopedAction(Commands.Back) ?: return
    val forward = rememberScopedAction(Commands.Forward) ?: return
    val popTo = rememberScopedCommand(Commands.PopTo) ?: return
    val s = LocalStrings.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Back / forward are always present so the breadcrumb never shifts; each
        // greys out and stops responding when there is nowhere to go that way.
        NavArrow(enabled = nav.canGoBack, mirrored = false, contentDescription = s.navBack, onClick = back)
        // Forward: the back arrow mirrored, so the pair matches exactly.
        NavArrow(enabled = nav.canGoForward, mirrored = true, contentDescription = s.navForward, onClick = forward)
        val trail = nav.trail
        trail.forEachIndexed { i, screen ->
            val isLast = i == trail.lastIndex
            CrumbSegment(
                label = rememberCrumbLabel(screen),
                isLast = isLast,
                onClick = { if (!isLast) popTo(screen) },
                // Where you ARE takes the remaining room and the places you came
                // through give way first. A trail four deep ending in a build
                // number -- Library, the pack, the project, 1.8.14-beta.1+1.21.1-neoforge
                // -- ran past the bar, and a Row measures its unweighted children
                // first, so the one segment that got cut was the one naming where
                // the reader had arrived.
                modifier = if (isLast) Modifier.weight(1f, fill = false)
                else Modifier.widthIn(max = CRUMB_MAX),
            )
            if (!isLast) {
                Symbol(
                    icon = NxIcon.ChevronRight,
                    contentDescription = null,
                    tint = NxInk.quiet,
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
            tint = NxInk.quiet.copy(alpha = if (enabled) 1f else 0.3f),
            size = 18.dp,
        )
    }
}

@Composable
private fun CrumbSegment(label: String, isLast: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = if (isLast) NxInk.main else NxInk.quiet,
        fontWeight = if (isLast) FontWeight.SemiBold else FontWeight.Normal,
        maxLines = 1,
        // Said with an ellipsis rather than cut. maxLines alone leaves the default
        // clip, which ends a name on half a glyph.
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .then(if (isLast) Modifier else Modifier.clickable(onClick = onClick))
            .padding(horizontal = 4.dp, vertical = 4.dp),
    )
}

/** How wide a place the reader has already left may be before it gives way. */
private val CRUMB_MAX = 160.dp
