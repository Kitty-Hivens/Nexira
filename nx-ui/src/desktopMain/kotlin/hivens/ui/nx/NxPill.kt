package hivens.ui.nx

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.text.style.TextOverflow

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
 * Deliberately not a Material chip: those size their container for a label plus
 * the touch target a phone needs, so the pill came out half again taller than
 * the text it wrapped and outweighed the value it annotated.
 */
@Composable
internal fun NxPill(
    text: String,
    container: Color,
    label: Color,
    modifier: Modifier = Modifier,
    /**
     * The hairline around the shell.
     *
     * The same axis the other two colours are on, and for the same reason: a
     * muted fact and a green "installed" want different outlines, and nothing
     * else about the shell changes between them. Transparent draws none.
     */
    border: Color = Color.Transparent,
    fontWeight: FontWeight? = null,
    dot: Color? = null,
    /**
     * A mark drawn where [dot] would be, and in its place.
     *
     * A coloured square is one kind of mark and a glyph is another: a loader chip
     * wants its own logo rather than a swatch standing in for it. The slot takes
     * precedence because a caller passing both means the drawn one.
     */
    leading: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val shape = RoundedCornerShape(pillCorner)
    Row(
        modifier = modifier
            .height(pillHeight)
            .clip(shape)
            .background(container)
            .then(if (border.alpha > 0f) Modifier.border(pillBorder, border, shape) else Modifier)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = pillPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(pillGap),
    ) {
        // The dot takes the shell's corner, so it stays the same shape as what holds it.
        when {
            leading != null -> leading()
            dot != null -> Box(Modifier.size(dotSize).clip(shape).background(dot))
        }
        Text(
            text       = text,
            style      = MaterialTheme.typography.bodyMedium,
            color      = label,
            fontWeight = fontWeight,
            maxLines   = 1,
            // A caller that caps the shell's width means the label to yield, and a
            // hard clip yields by cutting a glyph in half. This says so instead.
            overflow   = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Measured off the reference's tag, not chosen: 14 normal, a hairline, eight in
 * from each end, four between a mark and its word, and a full round.
 *
 * The label used to be labelSmall, 11 and medium. In a sidebar block whose title
 * is 18 and whose group labels are 16, an 11 chip read as a footnote to the
 * thing it was supposed to BE -- a block holding one tag looked like a card
 * somebody had forgotten to fill. Everything else in that block had already been
 * measured against the reference and matched; the chip was the one piece still
 * carrying a guess.
 */
private val pillCorner = CornerSize(50)
private val pillHeight = 24.dp
private val pillPadding = 8.dp
private val pillGap = 4.dp
private val pillBorder = 1.dp
private val dotSize = 7.dp
