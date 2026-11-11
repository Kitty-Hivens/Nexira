package hivens.ui.nx

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import hivens.ui.theme.Spacing
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxInk
import hivens.ui.theme.OnFill
import hivens.ui.theme.Status

/** What happened to the entry a [NxDiffRow] describes. */
enum class NxDiffRowKind { Added, Removed, Updated }

/**
 * One entry of a content diff: a kind badge (+ / - / ~ in the kind's accent),
 * an optional [leading] slot (icon/avatar, supplied by the caller so the
 * library stays free of image-loading dependencies), title + optional
 * subtitle, and an optional [trailing] detail (size, delta). The row owns the
 * add/remove/update colour vocabulary so screens never hand-roll green/red
 * rows (Rule 0).
 */
@Composable
fun NxDiffRow(
    kind: NxDiffRowKind,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    trailing: String? = null,
    leading: (@Composable () -> Unit)? = null,
) {
    val (status, glyph) = when (kind) {
        NxDiffRowKind.Added   -> Status.Success to "+"
        NxDiffRowKind.Removed -> Status.Error to "-"
        NxDiffRowKind.Updated -> Status.Warning to "~"
    }
    val dim = if (kind == NxDiffRowKind.Removed) 0.6f else 1f
    val badge = NxColor.wash(NxColor.status(status), 0.16f)
    Row(
        modifier              = modifier.fillMaxWidth().padding(vertical = 3.dp),
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.s10),
    ) {
        Box(
            modifier         = Modifier.size(20.dp).clip(RoundedCornerShape(6.dp)).background(badge),
            contentAlignment = Alignment.Center,
        ) {
            OnFill(badge) {
                Text(glyph, style = MaterialTheme.typography.labelMedium, color = NxColor.status(status, text = true), fontWeight = FontWeight.Bold)
            }
        }
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            Text(
                text       = title,
                style      = MaterialTheme.typography.bodyMedium,
                color      = NxInk.main.copy(alpha = dim),
                fontWeight = FontWeight.Medium,
                maxLines   = 1,
                overflow   = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text     = subtitle,
                    style    = MaterialTheme.typography.labelSmall,
                    color    = NxInk.quiet.copy(alpha = dim),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Text(trailing, style = MaterialTheme.typography.labelSmall, color = NxInk.quiet)
        }
    }
}
