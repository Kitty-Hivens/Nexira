package hivens.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import hivens.ui.theme.NxTheme

/**
 * Centred loading spinner, the launcher's one async-loading shape. The
 * caller passes the box fill via [modifier] (usually `fillMaxSize`), so a
 * surface drops it in while a scan / fetch / file read is in flight instead
 * of rendering a blank pane.
 */
@Composable
internal fun CenteredProgress(modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            color       = NxTheme.colors.primary.copy(alpha = 0.6f),
            strokeWidth = 2.dp,
            modifier    = Modifier.size(28.dp),
        )
    }
}
