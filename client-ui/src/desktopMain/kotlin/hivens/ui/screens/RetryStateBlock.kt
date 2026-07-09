package hivens.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hivens.ui.nx.NxButton
import hivens.ui.theme.NxTheme

/**
 * Centred error placeholder: title + message + a single retry button.
 * Shared between the Browse catalog and the Content tab, which surface
 * the same shape with their own copy. [titleStyle] and [spacing] let a
 * caller match its local emphasis; [modifier] carries the box fill +
 * padding so each caller keeps its own outer layout.
 */
@Composable
internal fun RetryStateBlock(
    title: String,
    message: String,
    retryLabel: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    titleStyle: TextStyle = MaterialTheme.typography.titleLarge,
    spacing: Dp = 12.dp,
) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing),
        ) {
            Text(
                text       = title,
                style      = titleStyle,
                color      = NxTheme.colors.error,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text      = message,
                style     = MaterialTheme.typography.bodySmall,
                color     = NxTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
                modifier  = Modifier.widthIn(max = 480.dp),
            )
            NxButton(label = retryLabel, onClick = onRetry)
        }
    }
}
