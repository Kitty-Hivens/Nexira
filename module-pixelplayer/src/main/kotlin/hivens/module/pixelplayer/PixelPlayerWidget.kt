package hivens.module.pixelplayer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hivens.widget.api.rememberProps
import hivens.widget.model.PropLabel
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import org.jetbrains.skia.Image as SkiaImage
import java.nio.file.Paths

/**
 * Tunables the editor writes. Everything is a scalar because the generated prop
 * form handles scalars; the extension list is a typed string for the same reason,
 * and it is parsed the way a person types it.
 */
@Serializable
data class PixelPlayerProps(
    @PropLabel("Folder") val folder: String = "",
    @PropLabel("Include subfolders") val recursive: Boolean = true,
    @PropLabel("Extensions") val extensions: String = "mp3,flac,m4a,wav",
    @PropLabel("Show artwork") val artwork: Boolean = true,
)

/**
 * A music player that belongs to this module.
 *
 * It draws with its own palette and its own metrics, decodes with its own engine,
 * and imports nothing from the launcher's UI -- which is the whole claim being
 * tested: the kernel takes a widget from a module it was not compiled with.
 */
@Widget(
    id = "pixelplayer.bar",
    displayName = "Pixel player",
    propsClass = PixelPlayerProps::class,
)
@Composable
fun PixelPlayerWidget(instance: WidgetInstance) {
    val props = instance.rememberProps<PixelPlayerProps>()
    // Shared rather than per-instance: a second copy of this widget is another
    // view of the same playback. Nothing is disposed here on purpose -- an engine
    // owned by a composition is an engine that keeps playing after the
    // composition is gone.
    val player = PixelPlayer.shared
    DisposableEffect(player) {
        player.viewAttached()
        onDispose { player.viewDetached() }
    }

    // Rescan when the folder question changes, off the drawing thread: a song
    // library is a deep tree and walking it is not a frame's worth of work.
    LaunchedEffect(props.folder, props.recursive, props.extensions) {
        val tracks = withContext(Dispatchers.IO) {
            val root = runCatching { Paths.get(props.folder) }.getOrNull()
            if (root == null || props.folder.isBlank()) emptyList()
            else Playlist.scan(root, props.recursive, Playlist.parseExtensions(props.extensions))
        }
        player.setTracks(tracks)
    }

    val state by player.state.collectAsState()

    // Decoded once per track rather than per frame: the bytes only change on open.
    val cover: ImageBitmap? = remember(state.artwork) {
        state.artwork?.let { bytes ->
            runCatching { SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
        }
    }

    PixelBar(
        state = state,
        cover = if (props.artwork) cover else null,
        onToggle = player::toggle,
        onPrev = player::previous,
        onNext = player::next,
        onSeek = player::seekTo,
    )
}

/**
 * The bar itself, over plain data -- split from the widget so it can be rendered
 * off-screen without an audio device or a decode thread behind it.
 */
@Composable
internal fun PixelBar(
    state: PlayerState,
    cover: ImageBitmap?,
    onToggle: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSeek: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .width(420.dp)
            .background(PixelSkin.panel)
            .border(PixelSkin.edge, PixelSkin.panelEdge)
            .padding(PixelSkin.pad),
        verticalArrangement = Arrangement.spacedBy(PixelSkin.gap),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Artwork(cover)
            Spacer(Modifier.width(PixelSkin.gap + 2.dp))
            Column(Modifier.weight(1f)) {
                Line(
                    text = state.title.ifBlank { if (state.tracks.isEmpty()) "no folder" else "..." },
                    color = if (state.failed) PixelSkin.danger else PixelSkin.text,
                    size = PixelSkin.titleSize,
                    family = PixelSkin.text_,
                )
                Spacer(Modifier.height(3.dp))
                Line(
                    text = state.artist.ifBlank { positionLabel(state) },
                    color = PixelSkin.textDim,
                    size = PixelSkin.bodySize,
                    family = PixelSkin.text_,
                )
                Spacer(Modifier.height(6.dp))
                Line(
                    text = "${clock(state.positionMs)} / ${if (state.durationMs > 0) clock(state.durationMs) else "--:--"}",
                    color = PixelSkin.textDim,
                    size = PixelSkin.timeSize,
                )
            }
            Spacer(Modifier.width(PixelSkin.gap))
            GlyphButton(
                glyph = if (state.playing) PixelSkin.PAUSE else PixelSkin.PLAY,
                enabled = state.tracks.isNotEmpty(),
                onClick = onToggle,
                side = PixelSkin.playSide,
                glyphSize = PixelSkin.glyphSize,
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            GlyphButton(PixelSkin.PREV, state.tracks.isNotEmpty(), onPrev)
            Spacer(Modifier.width(PixelSkin.gap))
            SteppedBar(state.fraction, Modifier.weight(1f), onSeek)
            Spacer(Modifier.width(PixelSkin.gap))
            GlyphButton(PixelSkin.NEXT, state.tracks.isNotEmpty(), onNext)
        }
    }
}

private fun positionLabel(state: PlayerState): String = when {
    state.failed -> "cannot play this file"
    state.tracks.isEmpty() -> "set a folder in the widget props"
    else -> "${state.index + 1} / ${state.tracks.size}"
}

@Composable
private fun Line(
    text: String,
    color: androidx.compose.ui.graphics.Color,
    size: androidx.compose.ui.unit.TextUnit,
    family: androidx.compose.ui.text.font.FontFamily = PixelSkin.mono,
) {
    BasicText(
        text = text,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        style = TextStyle(color = color, fontSize = size, fontFamily = family),
    )
}

@Composable
private fun Artwork(cover: ImageBitmap?) {
    Box(
        Modifier.size(PixelSkin.artSide).background(PixelSkin.well).border(PixelSkin.edge, PixelSkin.panelEdge),
        contentAlignment = Alignment.Center,
    ) {
        if (cover != null) {
            androidx.compose.foundation.Image(
                bitmap = cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            BasicText(
                text = PixelSkin.NOTE,
                style = TextStyle(color = PixelSkin.accentDim, fontSize = 22.sp, fontFamily = PixelSkin.mono),
            )
        }
    }
}

@Composable
private fun GlyphButton(
    glyph: String,
    enabled: Boolean,
    onClick: () -> Unit,
    side: androidx.compose.ui.unit.Dp = PixelSkin.buttonSide,
    glyphSize: androidx.compose.ui.unit.TextUnit = PixelSkin.stepGlyphSize,
) {
    val fg = if (enabled) PixelSkin.text else PixelSkin.textDim
    Box(
        Modifier
            .size(side)
            .background(PixelSkin.well)
            .border(PixelSkin.edge, if (enabled) PixelSkin.panelEdge else PixelSkin.well)
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        BasicText(glyph, style = TextStyle(color = fg, fontSize = glyphSize, fontFamily = PixelSkin.mono))
    }
}

/**
 * Progress as discrete cells rather than a filled track.
 *
 * Drawn rather than written with block glyphs: a click has to land on an exact
 * fraction, and glyph advance widths differ per font, so a text bar would seek to
 * a different place depending on what face resolved.
 */
@Composable
private fun SteppedBar(fraction: Float, modifier: Modifier = Modifier, onSeek: (Float) -> Unit) {
    var widthPx by remember { mutableStateOf(0) }
    val cells = 28
    val filled = (fraction * cells).toInt().coerceIn(0, cells)

    Row(
        modifier = modifier
            .height(PixelSkin.barHeight)
            .background(PixelSkin.well)
            .border(PixelSkin.edge, PixelSkin.panelEdge)
            .padding(2.dp)
            .onSizeChanged { widthPx = it.width }
            .pointerInput(Unit) {
                detectTapGestures { offset -> if (widthPx > 0) onSeek(offset.x / widthPx) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    if (widthPx > 0) onSeek(change.position.x / widthPx)
                }
            },
        horizontalArrangement = Arrangement.spacedBy(1.dp),
    ) {
        repeat(cells) { i ->
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxSize()
                    .background(if (i < filled) PixelSkin.accent else PixelSkin.trackEmpty),
            )
        }
    }
}
