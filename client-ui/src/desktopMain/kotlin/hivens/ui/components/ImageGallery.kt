package hivens.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.size.Size
import hivens.ui.icons.IconKey
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.surface.NxSurface
import hivens.ui.surface.NxSurfaceLevel
import hivens.ui.theme.LocalStyle
import hivens.ui.theme.Motion
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * One gallery item: a still [Image] or a [Video]. The strip shows the light
 * [thumb]; an image opens full-res in the lightbox, a video opens in the player.
 * Splitting thumb from full keeps the strip cheap -- opening a pack pulls ~350px
 * previews, not a dozen multi-MB originals.
 */
sealed interface GalleryMedia {
    /** Light preview URL for the strip; null for a video with no image poster. */
    val thumb: String?

    data class Image(override val thumb: String, val full: String) : GalleryMedia
    data class Video(override val thumb: String?, val url: String) : GalleryMedia
}

/**
 * Zip parallel full + thumbnail URL lists into [GalleryMedia], classifying each
 * by extension ([isVideoUrl]). A video's poster is the thumb only when that thumb
 * is itself an image (else null -> a placeholder with a play badge).
 */
fun galleryMedia(full: List<String>, thumbs: List<String>): List<GalleryMedia> =
    full.mapIndexed { i, f ->
        val thumb = thumbs.getOrNull(i) ?: f
        if (isVideoUrl(f)) {
            GalleryMedia.Video(thumb = thumb.takeUnless { isVideoUrl(it) }, url = f)
        } else {
            GalleryMedia.Image(thumb = thumb, full = f)
        }
    }

/**
 * The narrowest a screenshot cell is allowed to get before the grid drops a
 * column. Below this a Minecraft screenshot stops being readable -- the HUD, the
 * inventory, whatever the shot was taken to show, all of it turns to texture.
 */
private val MIN_CELL = 300.dp

/** Shots are framed 2:1, the shape a widescreen game window crops to most kindly. */
private const val CELL_ASPECT = 2f

private val CELL_GAP = 12.dp

/**
 * Screenshot grid with a full-window lightbox.
 *
 * A grid rather than a strip: the strip gave every shot the same 220x124 box and
 * cropped whatever did not fit, which is the worst size a screenshot can be --
 * too small to read and too cropped to recognise. Cells here are at least
 * [MIN_CELL] wide and share the row evenly, so a wide window shows bigger shots
 * rather than more of the same small ones, and every shot is on screen at once
 * instead of behind a sideways scroll.
 *
 * Tapping a shot opens [GalleryLightbox] over the whole window; arrows /
 * arrow-keys page, the scrim / Esc / close button dismiss.
 */
@Composable
fun ImageGallery(media: List<GalleryMedia>, modifier: Modifier = Modifier) {
    if (media.isEmpty()) return
    // The lightbox pages images only; videos open in the player. The image index
    // a cell click sets is into this filtered list.
    val images = remember(media) { media.filterIsInstance<GalleryMedia.Image>() }
    var lightbox by remember(media) { mutableStateOf<Int?>(null) }
    var video by remember(media) { mutableStateOf<GalleryMedia.Video?>(null) }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        // How many cells of at least MIN_CELL fit, counting the gaps between
        // them. One column always, however narrow the pane gets.
        val columns = (((maxWidth + CELL_GAP) / (MIN_CELL + CELL_GAP)).toInt()).coerceAtLeast(1)
        Column(verticalArrangement = Arrangement.spacedBy(CELL_GAP)) {
            media.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(CELL_GAP)) {
                    row.forEach { item ->
                        GalleryCell(
                            item     = item,
                            modifier = Modifier.weight(1f),
                            onClick  = {
                                when (item) {
                                    is GalleryMedia.Image -> lightbox = images.indexOf(item)
                                    is GalleryMedia.Video -> video = item
                                }
                            },
                        )
                    }
                    // A short last row keeps the cell width of a full one instead
                    // of one shot stretching across the pane.
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }

    lightbox?.let { idx ->
        if (idx in images.indices) {
            GalleryLightbox(
                images        = images,
                index         = idx,
                onIndexChange = { lightbox = it },
                onDismiss     = { lightbox = null },
            )
        }
    }
    video?.let { v ->
        FullscreenVideo(url = v.url, posterUrl = v.thumb, onDismiss = { video = null })
    }
}

/** One framed shot: the image at [CELL_ASPECT], on a plane with the library's own edge. */
@Composable
private fun GalleryCell(item: GalleryMedia, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(LocalStyle.current.cardCorner)
    // The surface draws the hover state itself, inside its own clip. Left to the
    // clickable it was painted on the node above, which is not clipped to the
    // shape, so a rounded cell lit up as a square with its corners filled in.
    val interaction = remember { MutableInteractionSource() }
    NxSurface(
        level    = NxSurfaceLevel.Raised,
        shape    = shape,
        // No blur behind a cell that is about to be covered by a photograph.
        glass    = false,
        interactionSource = interaction,
        modifier = modifier
            .aspectRatio(CELL_ASPECT)
            .clip(shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
    ) {
        when (item) {
            is GalleryMedia.Image -> AsyncImage(
                model              = item.thumb,
                contentDescription = null,
                contentScale       = ContentScale.Crop,
                modifier           = Modifier.fillMaxSize().clip(shape),
            )
            is GalleryMedia.Video -> {
                if (item.thumb != null) {
                    AsyncImage(
                        model              = item.thumb,
                        contentDescription = null,
                        contentScale       = ContentScale.Crop,
                        modifier           = Modifier.fillMaxSize().clip(shape),
                    )
                }
                Box(
                    modifier         = Modifier.align(Alignment.Center).size(48.dp).clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.5f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Symbol(NxIcon.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
                }
            }
        }
    }
}

@Composable
private fun GalleryLightbox(
    images: List<GalleryMedia.Image>,
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
        val scope = rememberCoroutineScope()
        val ctx = LocalPlatformContext.current
        // Live horizontal drag offset of the photo. Drag pages it; pulling past the
        // first / last shot meets resistance and springs back like jelly (rubber-band).
        val offsetX = remember(images) { Animatable(0f) }
        var boxW by remember(images) { mutableStateOf(1f) }
        val jelly = spring<Float>(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
        val gallerySlide = Motion.panelSlide.of<Float>()

        // Commit a page move with slide-out -> swap -> slide-in; spring back to centre
        // when there is no neighbour in that direction.
        fun page(dir: Int) {
            if (index + dir !in images.indices) {
                scope.launch { offsetX.animateTo(0f, jelly) }
                return
            }
            scope.launch {
                offsetX.animateTo(-dir * boxW, gallerySlide)
                onIndexChange(index + dir)
                offsetX.snapTo(dir * boxW)
                offsetX.animateTo(0f, gallerySlide)
            }
        }

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
                        Key.DirectionLeft  -> { page(-1); true }
                        Key.DirectionRight -> { page(1); true }
                        else               -> false
                    }
                }
                // Click on the backdrop closes.
                .clickable(interactionSource = scrim, indication = null, onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { boxW = it.width.toFloat().coerceAtLeast(1f) }
                    .pointerInput(images, index) {
                        detectHorizontalDragGestures(
                            onHorizontalDrag = { change, delta ->
                                change.consume()
                                // Dampen the pull when there's nowhere to go -- the photo
                                // gives a little, the release spring brings it home.
                                val atEdge = (delta > 0 && index == 0) || (delta < 0 && index == images.lastIndex)
                                scope.launch { offsetX.snapTo(offsetX.value + delta * if (atEdge) 0.32f else 1f) }
                            },
                            onDragEnd = {
                                val o = offsetX.value
                                val threshold = boxW * 0.18f
                                when {
                                    o <= -threshold -> page(1)
                                    o >=  threshold -> page(-1)
                                    else            -> scope.launch { offsetX.animateTo(0f, jelly) }
                                }
                            },
                        )
                    }
                    // Tap (no drag) dismisses; a real drag cancels the tap, so the two
                    // gestures coexist on the same node instead of fighting.
                    .pointerInput(Unit) { detectTapGestures(onTap = { onDismiss() }) },
                contentAlignment = Alignment.Center,
            ) {
                // Full-res decode (Size.ORIGINAL) of the full-res source URL; offset by
                // the live drag. A click anywhere on the photo dismisses -- the nav
                // arrows / close button sit on top and consume their clicks first.
                AsyncImage(
                    model              = remember(images, index) {
                        ImageRequest.Builder(ctx).data(images[index].full).size(Size.ORIGINAL).build()
                    },
                    contentDescription = null,
                    contentScale       = ContentScale.Fit,
                    modifier           = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 80.dp, vertical = 56.dp)
                        .offset { IntOffset(offsetX.value.roundToInt(), 0) }
                        .clip(RoundedCornerShape(LocalStyle.current.cardCorner)),
                )
            }

            OverlayButton(NxIcon.Close, onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd).padding(16.dp))

            if (index > 0) {
                OverlayButton(NxIcon.ChevronLeft, onClick = { page(-1) },
                    modifier = Modifier.align(Alignment.CenterStart).padding(12.dp))
            }
            if (index < images.lastIndex) {
                OverlayButton(NxIcon.ChevronRight, onClick = { page(1) },
                    modifier = Modifier.align(Alignment.CenterEnd).padding(12.dp))
            }

            if (images.size > 1) {
                Text(
                    text     = "${index + 1} / ${images.size}",
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
