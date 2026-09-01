package hivens.ui.nx

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import hivens.ui.theme.Form

/**
 * The badge shell every small labelled tag in the app is cut from: one height,
 * one corner, one gap, all read from the active style's badge spec so a skin can
 * turn every badge square in one step.
 *
 * Colours are the caller's, because that is the only axis the badges genuinely
 * differ on -- [NxMetaChip] derives them from a tone, the source badge from the
 * source's brand hue. The optional [dot] is the leading state marker used where a
 * badge reports a live state (a launch in flight, a pending build) rather than a
 * static fact.
 *
 * Deliberately not a Material chip: those size their container for a labelLarge
 * body, and every badge here draws labelSmall, so the pill came out half again
 * taller than the text it wrapped and outweighed the value it annotated.
 */
@Composable
internal fun NxPill(
    text: String,
    container: Color,
    label: Color,
    modifier: Modifier = Modifier,
    fontWeight: FontWeight? = null,
    dot: Color? = null,
    onClick: (() -> Unit)? = null,
) {
    val badge = Form.Badge
    val shape = badge.shape()
    Row(
        modifier = modifier
            .height(badge.height)
            .clip(shape)
            .background(container)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = badge.horizontalPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(badge.gap),
    ) {
        // The dot takes the badge's own corner, so a square skin gets a square
        // marker instead of a lone circle.
        if (dot != null) Box(Modifier.size(badge.dotSize).clip(shape).background(dot))
        Text(
            text       = text,
            style      = MaterialTheme.typography.labelSmall,
            color      = label,
            fontWeight = fontWeight,
            maxLines   = 1,
        )
    }
}
