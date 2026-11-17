package hivens.ui.nx

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import hivens.ui.theme.NeutralTheme
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxThemed

/**
 * Provenance pill: a short [label] on the source's brand [color]. Domain-neutral
 * on purpose -- the pack/mirror types live in client-core, so callers map their
 * origin to (label, color) and this only owns the look.
 *
 * Same [NxPill] shell as [NxMetaChip], so a card's source badge and its metadata
 * badges line up instead of standing at two different heights; the bold label is
 * the only thing that separates a provenance mark from a fact.
 */
@Composable
fun NxSourceBadge(
    label: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    // The brand colour is data and stays as given. The label is whichever ink reads on
    // it. It used to be white on every brand, which a bright one does not survive.
    val fill = NxColor.wash(color, 0.85f)
    NxPill(
        text       = label,
        container  = fill,
        label      = NxColor.on(fill),
        modifier   = modifier,
        fontWeight = FontWeight.Bold,
    )
}

@Preview
@Composable
private fun NxSourceBadgeDarkPreview() = SourceBadges(dark = true)

@Preview
@Composable
private fun NxSourceBadgeLightPreview() = SourceBadges(dark = false)

@Composable
private fun SourceBadges(dark: Boolean) {
    NxThemed(NeutralTheme, dark = dark) {
        Row(
            modifier              = Modifier.width(420.dp).background(NxColor.page).padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            NxSourceBadge("Catalogue", Color(0xFF1BD96A))
            NxSourceBadge("Mirror", Color(0xFFF16436))
            NxSourceBadge("Local", Color(0xFF3A4A8C))
            NxSourceBadge("Bright", Color(0xFFFFE14D))
        }
    }
}
