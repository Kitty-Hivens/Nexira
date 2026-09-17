package hivens.ui.nx

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow

/**
 * A label whose box does not change size with the state it is drawn in.
 *
 * Selecting a tab or a chip bolds its label, and a heavier face is wider, so the
 * pill grew the moment it was clicked and every pill after it slid sideways --
 * the control moved out from under the cursor that had just pressed it. The
 * heaviest face the label can ever wear measures the box; the face it wears
 * right now is drawn inside that box. Nothing shifts, and selection keeps the
 * weight that says it is selected.
 *
 * [widest] is the heaviest of the weights a call site swaps between, which is
 * what it has to reserve for. Sizing off a face the label never takes would pad
 * every one of them with a gap that belongs to no state.
 */
@Composable
fun NxSteadyText(
    text: String,
    weight: FontWeight,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
    widest: FontWeight = FontWeight.Bold,
    maxLines: Int = 1,
    overflow: TextOverflow = TextOverflow.Ellipsis,
    textAlign: TextAlign? = null,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        // Measures and never paints. Cleared out of the semantics tree as well,
        // or a reader would meet every label twice.
        Text(
            text       = text,
            style      = style,
            fontWeight = widest,
            maxLines   = maxLines,
            overflow   = overflow,
            modifier   = Modifier.alpha(0f).clearAndSetSemantics {},
        )
        Text(
            text       = text,
            style      = style,
            color      = color,
            fontWeight = weight,
            maxLines   = maxLines,
            overflow   = overflow,
            textAlign  = textAlign,
        )
    }
}
