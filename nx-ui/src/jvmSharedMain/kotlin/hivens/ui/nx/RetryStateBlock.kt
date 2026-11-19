package hivens.ui.nx

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hivens.ui.nx.NxButton
import hivens.ui.theme.NeutralTheme
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxThemed
import hivens.ui.theme.Status

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
        titleColor = NxColor.status(Status.Error),
        titleStyle = titleStyle,
        spacing = spacing,
        action = { NxButton(label = retryLabel, onClick = onRetry) },
    )
}

@Preview
@Composable
private fun RetryStateBlockDarkPreview() = RetryStateBlocks(dark = true)

@Preview
@Composable
private fun RetryStateBlockLightPreview() = RetryStateBlocks(dark = false)

@Composable
private fun RetryStateBlocks(dark: Boolean) {
    NxThemed(NeutralTheme, dark = dark) {
        Column(
            modifier            = Modifier.width(420.dp).background(NxColor.page).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(32.dp),
        ) {
            RetryStateBlock("Could not load", "The server answered with an error.", retryLabel = "Retry", onRetry = {}, modifier = Modifier.fillMaxWidth())
            RetryStateBlock(
                modifier   = Modifier.fillMaxWidth(),
                title      = "Download failed",
                message    = "The connection dropped halfway through.",
                retryLabel = "Download again",
                onRetry    = {},
                titleStyle = MaterialTheme.typography.titleMedium,
                spacing    = 8.dp,
            )
        }
    }
}
