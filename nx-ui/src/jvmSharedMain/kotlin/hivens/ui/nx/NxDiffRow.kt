package hivens.ui.nx

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxInk
import hivens.ui.theme.OnFill
import hivens.ui.theme.Previewed
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
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier         = Modifier.size(20.dp).clip(RoundedCornerShape(6.dp)).background(badge),
            contentAlignment = Alignment.Center,
        ) {
            OnFill(badge) {
                Text(glyph, style = MaterialTheme.typography.labelMedium, color = NxColor.status(status), fontWeight = FontWeight.Bold)
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

@Preview
@Composable
private fun NxDiffRowDarkPreview() = DiffRowKinds(dark = true)

@Preview
@Composable
private fun NxDiffRowLightPreview() = DiffRowKinds(dark = false)

@Composable
private fun DiffRowKinds(dark: Boolean) {
    Previewed(dark = dark) {
        Column(Modifier.width(420.dp).background(NxColor.page).padding(16.dp)) {
            NxDiffRow(NxDiffRowKind.Added, "Sodium", subtitle = "0.6.0 for Fabric", trailing = "1.2 MB")
            NxDiffRow(NxDiffRowKind.Removed, "OptiFine", subtitle = "Replaced by Sodium", trailing = "-4.8 MB")
            NxDiffRow(NxDiffRowKind.Updated, "Lithium", subtitle = "0.12.1 to 0.13.0")
            NxDiffRow(NxDiffRowKind.Added, "Iris Shaders", trailing = "3.1 MB") {
                Box(Modifier.size(24.dp).clip(RoundedCornerShape(6.dp))) { InitialsAvatar("Iris Shaders", NxColor.hashed("Iris")) }
            }
        }
    }
}
