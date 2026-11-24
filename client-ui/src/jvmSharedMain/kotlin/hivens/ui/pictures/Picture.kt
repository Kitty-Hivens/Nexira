package hivens.ui.pictures

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage

/**
 * The picture at [url], cropped to fill, over [placeholder].
 *
 * The placeholder stands while the picture is on its way and stays where there is none to
 * come, so an address that no longer answers leaves no hole. It is let go once the picture
 * is drawn, since it would show through a picture with clear parts.
 */
@Composable
internal fun Picture(
    url: String?,
    modifier: Modifier = Modifier,
    placeholder: @Composable BoxScope.() -> Unit,
) {
    Box(modifier) {
        var drawn by remember(url) { mutableStateOf(false) }
        if (!drawn) placeholder()
        if (!url.isNullOrBlank()) {
            AsyncImage(
                model              = url,
                contentDescription = null,
                contentScale       = ContentScale.Crop,
                modifier           = Modifier.matchParentSize(),
                onSuccess          = { drawn = true },
            )
        }
    }
}
