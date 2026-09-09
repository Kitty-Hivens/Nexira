package hivens.ui.nx

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size as GeomSize
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
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
 * Four concepts for a small player, one per sheet.
 *
 * Not four arrangements of the same card. Each answers "what is a small player"
 * differently, and each answers the corpus fact that a cover is usually absent
 * differently: A never draws one, B is nothing but one, C refuses the question,
 * D has no room for one.
 */
class PlayerConceptsProbe {

    private val title = "Зумеры оказались умнее всех"
    private val artist = "ФинБош"
    private val elapsed = "5:17"
    private val total = "20:16"
    private val fraction = 317f / 1216f

    private val cover: ImageBitmap by lazy {
        val bmp = Bitmap().apply { allocPixels(ImageInfo.makeN32Premul(360, 360)) }
        val c = SkCanvas(bmp)
        c.clear(0xFF1B2440.toInt())
        val p = SkPaint()
        p.color = 0xFFE8C36B.toInt(); c.drawCircle(130f, 150f, 84f, p)
        p.color = 0xFFB03A2E.toInt(); c.drawRect(SkRect.makeXYWH(0f, 250f, 360f, 110f), p)
        p.color = 0xFF2E86AB.toInt(); c.drawRect(SkRect.makeXYWH(230f, 40f, 92f, 92f), p)
        SkImage.makeFromBitmap(bmp).toComposeImageBitmap()
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun sheet(name: String, wDp: Int, hDp: Int, body: @Composable () -> Unit) {
        val d = 3f
        val scene = ImageComposeScene((wDp * d).toInt(), (hDp * d).toInt(), density = Density(d)) {
            NxTheme(useDarkTheme = true) {
                Box(
                    Modifier.fillMaxSize().background(NxTheme.colors.background).padding(Spacing.s16),
                    contentAlignment = Alignment.Center,
                ) { body() }
            }
        }
        val img = scene.render()
        scene.close()
        File("build/render").mkdirs()
        img.encodeToData(EncodedImageFormat.PNG)?.bytes?.let { File("build/render/concept-$name.png").writeBytes(it) }
    }

    @Composable
    private fun Glyph(icon: hivens.ui.icons.IconKey, size: Int, alpha: Float = 1f) =
        Symbol(
            icon, null,
            tint = NxTheme.colors.textPrimary.copy(alpha = alpha),
            fill = 1f, weight = 500,
            modifier = Modifier.size(size.dp),
        )

    // ── A. The body is the timeline ────────────────────────────────────────
    // There is no progress bar because the card itself is the measure: the
    // played part of the track is the filled part of the plane. Position is read
    // from how full the object is, which is one element doing one job instead of
    // a bar competing with a volume slider of the same shape.
    @Composable
    private fun ConceptTimeline(name: String) {
        val c = NxTheme.colors
        NxSurface(NxSurfaceLevel.Floating, Modifier.width(320.dp), shape = MaterialTheme.shapes.medium) {
            Box(Modifier.fillMaxWidth().height(76.dp)) {
                Box(
                    Modifier.fillMaxWidth(fraction).fillMaxHeight()
                        .background(lerp(c.surfaceContainer, c.primary, 0.22f)),
                )
                Row(
                    Modifier.fillMaxSize().padding(horizontal = Spacing.s14),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            name, style = MaterialTheme.typography.bodyLarge, color = c.textPrimary,
                            fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(Spacing.s2))
                        Text(
                            "$artist  ·  $elapsed / $total",
                            style = MaterialTheme.typography.bodySmall, color = c.textSecondary, maxLines = 1,
                        )
                    }
                    Spacer(Modifier.width(Spacing.s12))
                    Glyph(NxIcon.SkipPrevious, 18, 0.7f)
                    Spacer(Modifier.width(Spacing.s10))
                    Glyph(NxIcon.Pause, 22)
                    Spacer(Modifier.width(Spacing.s10))
                    Glyph(NxIcon.SkipNext, 18, 0.7f)
                }
            }
        }
    }

    // ── B. The cover is the whole object ───────────────────────────────────
    // At rest nothing but the picture, the way Clapper shows nothing but the
    // frame. With no cover the ground is generated from the palette rather than
    // faked with a note glyph, so the empty case is a colour, not a placeholder.
    @Composable
    private fun ConceptCoverTile(art: ImageBitmap?, chrome: Boolean) {
        val c = NxTheme.colors
        Box(Modifier.size(196.dp).clip(MaterialTheme.shapes.medium)) {
            if (art != null) {
                Image(art, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.linearGradient(
                            listOf(
                                lerp(c.surfaceContainer, c.primary, 0.30f),
                                lerp(c.surfaceContainer, c.tertiary, 0.16f),
                            ),
                        ),
                    ),
                )
            }
            if (chrome) {
                // Flat dim first, because the transport sits in the MIDDLE and a
                // bottom gradient leaves it on whatever the cover happens to be.
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.42f)))
                Row(
                    Modifier.align(Alignment.Center),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.s16),
                ) {
                    Glyph(NxIcon.SkipPrevious, 20, 0.85f)
                    Glyph(NxIcon.Pause, 30)
                    Glyph(NxIcon.SkipNext, 20, 0.85f)
                }
            }
            // The bottom gradient is for the caption alone, and it is there whether
            // the chrome is up or not, because the title sits on the picture either way.
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.72f)),
                ),
            )
            Column(Modifier.align(Alignment.BottomStart).padding(Spacing.s12)) {
                Text(
                    title, style = MaterialTheme.typography.bodyMedium, color = Color.White,
                    fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
                Text(artist, style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = 0.75f))
            }
            // Inset by the shape's own corner so neither end is eaten by the curve.
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 12.dp)) {
                Box(Modifier.fillMaxWidth(fraction).height(3.dp).clip(CircleShape).background(c.primary))
            }
        }
    }

    // ── C. A readout, never a picture ──────────────────────────────────────
    // The corpus is files with no art, so this one stops pretending: the hero is
    // the time, the way a departure board's hero is the time. Nothing here can
    // look empty, because nothing here was ever going to be a picture.
    @Composable
    private fun ConceptReadout() {
        val c = NxTheme.colors
        NxSurface(NxSurfaceLevel.Floating, Modifier.width(320.dp), shape = MaterialTheme.shapes.medium) {
            Column(Modifier.fillMaxWidth().padding(Spacing.s14)) {
                Text(
                    title, style = MaterialTheme.typography.bodyMedium, color = c.textSecondary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(Spacing.s6))
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(elapsed, style = MaterialTheme.typography.headlineMedium, color = c.textPrimary, fontWeight = FontWeight.Medium)
                    Spacer(Modifier.width(Spacing.s6))
                    Text(
                        "/ $total", style = MaterialTheme.typography.bodyMedium, color = c.textSecondary,
                        modifier = Modifier.padding(bottom = 3.dp),
                    )
                    Spacer(Modifier.weight(1f))
                    Glyph(NxIcon.SkipPrevious, 18, 0.7f)
                    Spacer(Modifier.width(Spacing.s10))
                    Glyph(NxIcon.Pause, 22)
                    Spacer(Modifier.width(Spacing.s10))
                    Glyph(NxIcon.SkipNext, 18, 0.7f)
                }
                Spacer(Modifier.height(Spacing.s10))
                Box(Modifier.fillMaxWidth().height(2.dp)) {
                    Box(Modifier.fillMaxSize().background(c.textSecondary.copy(alpha = 0.20f)))
                    Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(c.primary))
                }
            }
        }
    }

    // ── D. A token, the smallest presence that still plays ─────────────────
    // One square grid cell. The ring is the measure, the glyph is the transport,
    // and the name is not shown at all: it is a tooltip away, which the library
    // now anchors under the control instead of chasing the pointer.
    @Composable
    private fun ConceptToken() {
        val c = NxTheme.colors
        NxSurface(NxSurfaceLevel.Floating, Modifier.size(96.dp), shape = CircleShape) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Canvas(Modifier.size(74.dp)) {
                    val stroke = 5.dp.toPx()
                    val inset = stroke / 2f
                    val arc = GeomSize(size.width - stroke, size.height - stroke)
                    drawArc(
                        color = c.textSecondary.copy(alpha = 0.22f),
                        startAngle = -90f, sweepAngle = 360f, useCenter = false,
                        topLeft = Offset(inset, inset), size = arc,
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                    drawArc(
                        color = c.primary,
                        startAngle = -90f, sweepAngle = 360f * fraction, useCenter = false,
                        topLeft = Offset(inset, inset), size = arc,
                        style = Stroke(width = stroke, cap = StrokeCap.Round),
                    )
                }
                Glyph(NxIcon.Pause, 26)
            }
        }
    }

    @Test
    fun probe() {
        sheet("a-timeline", 360, 116) { ConceptTimeline(title) }
        sheet("a-timeline-short", 360, 116) { ConceptTimeline("Bus Stop") }
        sheet("b-cover-rest", 236, 236) { ConceptCoverTile(cover, chrome = false) }
        sheet("b-cover-hover", 236, 236) { ConceptCoverTile(cover, chrome = true) }
        sheet("b-cover-none", 236, 236) { ConceptCoverTile(null, chrome = false) }
        sheet("c-readout", 360, 150) { ConceptReadout() }
        sheet("d-token", 136, 136) { ConceptToken() }
    }
}
