package hivens.ui.nx

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight

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
) = NxPill(
    text       = label,
    container  = color.copy(alpha = 0.85f),
    label      = Color.White,
    modifier   = modifier,
    fontWeight = FontWeight.Bold,
)
