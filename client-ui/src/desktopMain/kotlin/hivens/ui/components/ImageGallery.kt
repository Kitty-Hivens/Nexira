package hivens.ui.components

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import coil3.compose.AsyncImage
import hivens.ui.icons.IconKey
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol

/**
 * Horizontal screenshot strip with a full-window lightbox. The strip scrolls by
 * mouse drag and by the always-present [HorizontalScrollbar] (so shots past the
 * fifth are reachable -- a desktop LazyRow won't move on a vertical wheel), and
 * carries the standard overscroll so a drag past the end springs back. Tapping a
 * shot opens [GalleryLightbox] over the whole window; arrows / arrow-keys page,
 * the scrim / Esc / close button dismiss.
 */
@Composable
fun ImageGallery(urls: List<String>, modifier: Modifier = Modifier) {
    if (urls.isEmpty()) return
    var lightbox by remember(urls) { mutableStateOf<Int?>(null) }
    val listState = rememberLazyListState()

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LazyRow(
            state                 = listState,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            overscrollEffect      = rememberOverscrollEffect(),
            modifier              = Modifier.fillMaxWidth(),
        ) {
            itemsIndexed(urls) { i, url ->
                AsyncImage(
                    model              = url,
                    contentDescription = null,
                    contentScale       = ContentScale.Crop,
                    modifier           = Modifier
                        .width(220.dp)
                        .height(124.dp)
                        .clip(MaterialTheme.shapes.small)
                        .clickable { lightbox = i },
                )
            }
        }
        // Only worth a scrollbar once the strip actually overflows.
        if (urls.size > 3) {
            HorizontalScrollbar(
                adapter  = rememberScrollbarAdapter(listState),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    lightbox?.let { idx ->
        GalleryLightbox(
            urls          = urls,
            index         = idx,
            onIndexChange = { lightbox = it },
            onDismiss     = { lightbox = null },
        )
    }
}

@Composable
private fun GalleryLightbox(
    urls: List<String>,
    index: Int,
    onIndexChange: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    Popup(
        alignment        = Alignment.Center,
        onDismissRequest = onDismiss,
        properties       = PopupProperties(focusable = true),
    ) {
        val focus = remember { FocusRequester() }
        val scrim = remember { MutableInteractionSource() }
        val image = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f))
                .focusRequester(focus)
                .focusable()
                .onPreviewKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (ev.key) {
                        Key.Escape         -> { onDismiss(); true }
                        Key.DirectionLeft  -> { if (index > 0) onIndexChange(index - 1); true }
                        Key.DirectionRight -> { if (index < urls.lastIndex) onIndexChange(index + 1); true }
                        else               -> false
                    }
                }
                // Click on the backdrop closes; the image consumes its own clicks.
                .clickable(interactionSource = scrim, indication = null, onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            AsyncImage(
                model              = urls[index],
                contentDescription = null,
                contentScale       = ContentScale.Fit,
                modifier           = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 80.dp, vertical = 56.dp)
                    .clickable(interactionSource = image, indication = null, onClick = {}),
            )

            OverlayButton(NxIcon.Close, onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).padding(16.dp))

            if (index > 0) {
                OverlayButton(NxIcon.ChevronLeft, onClick = { onIndexChange(index - 1) },
                    modifier = Modifier.align(Alignment.CenterStart).padding(12.dp))
            }
            if (index < urls.lastIndex) {
                OverlayButton(NxIcon.ChevronRight, onClick = { onIndexChange(index + 1) },
                    modifier = Modifier.align(Alignment.CenterEnd).padding(12.dp))
            }

            if (urls.size > 1) {
                Text(
                    text     = "${index + 1} / ${urls.size}",
                    style    = MaterialTheme.typography.labelMedium,
                    color    = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 20.dp),
                )
            }
        }
        LaunchedEffect(Unit) { focus.requestFocus() }
    }
}

@Composable
private fun OverlayButton(icon: IconKey, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier         = modifier
            .size(40.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Symbol(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
    }
}
