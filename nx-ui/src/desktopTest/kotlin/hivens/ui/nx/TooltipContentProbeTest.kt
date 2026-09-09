package hivens.ui.nx

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hivens.ui.icons.NxIcon
import hivens.ui.surface.ChamferedRectShape
import hivens.ui.surface.NxSurface
import hivens.ui.surface.NxSurfaceLevel
import hivens.ui.theme.NxTheme
import hivens.ui.theme.Spacing
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Canvas as SkCanvas
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image as SkImage
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint as SkPaint
import org.jetbrains.skia.Rect as SkRect
import java.io.File
import kotlin.test.Test

/**
 * Throwaway: what each tooltip content actually draws, one per sheet at density 4.
 *
 * One kind per image and nothing else in the frame, because a contact sheet of all
 * four arrives resampled and everything that matters here (a glyph's weight, four
 * points of padding, where a baseline sits) is under the noise floor once it is.
 */
class TooltipContentProbeTest {

    private val cover: ImageBitmap by lazy {
        val bmp = Bitmap().apply { allocPixels(ImageInfo.makeN32Premul(240, 160)) }
        val c = SkCanvas(bmp)
        c.clear(0xFF23304A.toInt())
        val p = SkPaint()
        p.color = 0xFFE8C36B.toInt()
        c.drawCircle(80f, 80f, 46f, p)
        p.color = 0xFFB03A2E.toInt()
        c.drawRect(SkRect.makeXYWH(140f, 30f, 70f, 100f), p)
        SkImage.makeFromBitmap(bmp).toComposeImageBitmap()
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun sheet(name: String, wDp: Int, hDp: Int, shape: Shape? = null, body: @Composable () -> Unit) {
        val d = 4f
        val scene = ImageComposeScene((wDp * d).toInt(), (hDp * d).toInt(), density = Density(d)) {
            NxTheme(useDarkTheme = true) {
                Box(Modifier.fillMaxSize().background(NxTheme.colors.background), contentAlignment = Alignment.Center) {
                    NxSurface(
                        level = NxSurfaceLevel.Floating,
                        blurDp = 0f,
                        opacity = 1f,
                        shape = shape ?: MaterialTheme.shapes.extraSmall,
                        shadowDp = 6f,
                    ) {
                        Box(
                            Modifier
                                .widthIn(max = 320.dp)
                                .padding(PaddingValues(horizontal = Spacing.s8, vertical = Spacing.s4)),
                        ) { body() }
                    }
                }
            }
        }
        val img = scene.render()
        scene.close()
        val out = File("build/render").apply { mkdirs() }
        img.encodeToData(EncodedImageFormat.PNG)?.bytes?.let { File(out, "tip-$name.png").writeBytes(it) }
    }

    @Test
    fun probe() {
        sheet("label", 150, 40) { NxTooltipLabel("Open the containing folder") }
        sheet("glyph", 150, 40) { NxTooltipGlyphLabel(NxIcon.FolderOpen, "Open the containing folder") }
        sheet("titled", 220, 70) {
            NxTooltipTitled(
                title = "Hardware decode",
                description = "Offloads video to the GPU. Falls back per file when no device opens.",
                shortcut = "Ctrl+H",
            )
        }
        sheet("preview", 190, 150) {
            NxTooltipPreview(caption = "cover.png, 240 by 160") {
                Image(
                    cover, null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(width = 160.dp, height = 106.dp).clip(MaterialTheme.shapes.extraSmall),
                )
            }
        }
        // The long hexagon as a tooltip body, which is the form this shape was
        // asked for. Small cut so the points read as ends rather than as arrows.
        sheet("chamfer", 190, 44, shape = ChamferedRectShape(startCutDp = 9f, endCutDp = 9f, roundingDp = 1.5f)) {
            Box(Modifier.padding(horizontal = Spacing.s6)) { NxTooltipLabel("Rendered on the GPU") }
        }
    }
}
