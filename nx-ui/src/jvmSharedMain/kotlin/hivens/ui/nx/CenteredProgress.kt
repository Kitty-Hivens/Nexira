package hivens.ui.nx

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import hivens.ui.theme.NeutralTheme
import hivens.ui.theme.NxColor
import hivens.ui.theme.NxThemed

/**
 * Centred loading spinner, the launcher's one async-loading shape. The
 * caller passes the box fill via [modifier] (usually `fillMaxSize`), so a
 * surface drops it in while a scan / fetch / file read is in flight instead
 * of rendering a blank pane.
 */
@Composable
fun CenteredProgress(modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            color       = NxColor.lead().copy(alpha = 0.6f),
            strokeWidth = 2.dp,
            modifier    = Modifier.size(28.dp),
        )
    }
}

@Preview
@Composable
private fun CenteredProgressDarkPreview() = CenteredProgressPane(dark = true)

@Preview
@Composable
private fun CenteredProgressLightPreview() = CenteredProgressPane(dark = false)

@Composable
private fun CenteredProgressPane(dark: Boolean) {
    NxThemed(NeutralTheme, dark = dark) {
        CenteredProgress(Modifier.size(240.dp, 120.dp).background(NxColor.page))
    }
}
