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
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight

/**
 * The shell every small labelled tag in the app is cut from: one height, one
 * corner, one gap, decided here so the tags cannot drift apart.
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
    val shape = RoundedCornerShape(pillCorner)
    Row(
        modifier = modifier
            .height(pillHeight)
            .clip(shape)
            .background(container)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = pillPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(pillGap),
    ) {
        // The dot takes the shell's corner, so it stays the same shape as what holds it.
        if (dot != null) Box(Modifier.size(dotSize).clip(shape).background(dot))
        Text(
            text       = text,
            style      = MaterialTheme.typography.labelSmall,
            color      = label,
            fontWeight = fontWeight,
            maxLines   = 1,
        )
    }
}

private val pillCorner = CornerSize(50)
private val pillHeight = 22.dp
private val pillPadding = 9.dp
private val pillGap = 6.dp
private val dotSize = 7.dp
