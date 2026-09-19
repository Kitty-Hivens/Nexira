package hivens.ui.nx

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hivens.ui.nx.NxButton
import hivens.ui.theme.NxTheme

/**
 * Something failed and can be asked again: a red headline, the reason, one retry.
 *
 * The shape lives in [NxStateBlock]; what this adds is the meaning. The colour is
 * not decoration here -- it is the difference between a pane that is empty because
 * something broke and one that is empty because there is nothing there.
 */
@Composable
fun RetryStateBlock(
    title: String,
    message: String,
    retryLabel: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    titleStyle: TextStyle = MaterialTheme.typography.titleLarge,
    spacing: Dp = 12.dp,
) {
    NxStateBlock(
        title = title,
        message = message,
        modifier = modifier,
        titleColor = NxTheme.colors.error,
        titleStyle = titleStyle,
        spacing = spacing,
        action = { NxButton(label = retryLabel, onClick = onRetry) },
    )
}
