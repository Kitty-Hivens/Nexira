package hivens.ui.background

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import hivens.ui.theme.WallpaperTone
import hivens.ui.theme.wallpaperToneFromImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File

private val log = LoggerFactory.getLogger("Wallpaper")

/** What a wallpaper file turned out to be: a picture with its pixels, or something that moves. */
private sealed interface Resolved {
    class Still(val bitmap: Bitmap) : Resolved
    data object Moving : Resolved
}

private class AnimatedFile : RuntimeException()

/**
 * A still goes through the system decoder, which downsamples to the screen while it reads and
 * handles WebP and HEIF. An animated image, a video, or anything the decoder refuses goes to
 * skinema, whose Android engine plays all of them.
 */
@Composable
internal actual fun rememberWallpaperPainter(
    file: File,
    settings: BackgroundSettings,
    onTone: (WallpaperTone) -> Unit,
    onAudioVolume: (Float) -> Unit,
): Painter? {
    val maxHeight = LocalContext.current.resources.displayMetrics.heightPixels
    var resolved by remember(file) { mutableStateOf<Resolved?>(null) }
    LaunchedEffect(file, maxHeight) {
        resolved = withContext(Dispatchers.IO) { resolve(file, maxHeight) }
    }
    return when (val kind = resolved) {
        null -> null
        is Resolved.Still -> rememberStillPainter(kind.bitmap, onTone)
        Resolved.Moving -> rememberSkinemaWallpaper(file, settings, onTone)
    }
}

@Composable
private fun rememberStillPainter(bitmap: Bitmap, onTone: (WallpaperTone) -> Unit): Painter {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    LaunchedEffect(image) {
        onTone(withContext(Dispatchers.Default) { wallpaperToneFromImage(image) })
    }
    return remember(image) { BitmapPainter(image) }
}

private fun resolve(file: File, maxHeight: Int): Resolved = try {
    val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
        if (info.isAnimated) throw AnimatedFile()
        // Software pixels: the colours are read back off this bitmap, which a hardware one cannot be.
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        val (width, height) = info.size.width to info.size.height
        if (maxHeight > 0 && height > maxHeight) {
            decoder.setTargetSize((width.toLong() * maxHeight / height).toInt().coerceAtLeast(1), maxHeight)
        }
    }
    Resolved.Still(bitmap)
} catch (_: AnimatedFile) {
    Resolved.Moving
} catch (e: Exception) {
    log.debug("The system decoder reads no picture in {}, leaving it to the player", file.absolutePath, e)
    Resolved.Moving
}
