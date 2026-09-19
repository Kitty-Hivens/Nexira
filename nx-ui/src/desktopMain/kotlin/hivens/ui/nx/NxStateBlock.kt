package hivens.ui.nx

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hivens.ui.icons.IconKey
import hivens.ui.icons.Symbol
import hivens.ui.theme.NxTheme

/**
 * A pane with nothing in it, saying why.
 *
 * The shared body under every such state: an optional mark, a headline in the
 * tone the state deserves, a sentence set to a reading measure, and at most one
 * thing to do about it. What differs between states is the colour of the headline
 * and whether there is anything to press, so those are arguments and the rest is
 * not repeated per screen.
 *
 * Callers use the named states below rather than this directly, because "an
 * error" and "not built yet" are different things to a reader and naming them the
 * same would be the first step to drawing them the same.
 */
@Composable
fun NxStateBlock(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    icon: IconKey? = null,
    titleColor: Color = NxTheme.colors.textPrimary,
    titleStyle: TextStyle = MaterialTheme.typography.titleLarge,
    spacing: Dp = 12.dp,
    action: (@Composable () -> Unit)? = null,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing),
        ) {
            if (icon != null) {
                Symbol(icon, contentDescription = null, tint = titleColor, size = 32.dp)
            }
            Text(
                text = title,
                style = titleStyle,
                color = titleColor,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = NxTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 480.dp),
            )
            action?.invoke()
        }
    }
}
