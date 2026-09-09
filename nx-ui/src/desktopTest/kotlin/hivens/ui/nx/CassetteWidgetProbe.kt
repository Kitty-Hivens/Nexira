package hivens.ui.nx

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size as GeomSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hivens.ui.icons.IconKey
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.surface.NxSurface
import hivens.ui.surface.NxSurfaceLevel
import hivens.ui.theme.NxTheme
import hivens.ui.theme.Spacing
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image as SkImage
import java.io.File
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test

/**
 * The cassette, assembled.
 *
 * Settled from the reference photographs and the per-component sheets. The face
 * carries two round openings of about 27 mm on a 63.8 mm body with their centres
 * 42 mm apart, because that is what the common compact cassette has and it is
 * also what tells the shape apart from a floppy, which has a shutter and no
 * wheels. Corners are 4 dp with a lit top edge, since 8 dp read as a card. The
 * inlay stays paper with the album art as a square sticker, which keeps the art
 * unclipped and keeps the object a cassette rather than a picture frame.
 *
 * Progress is the two pack radii, following `r = sqrt(hub^2 + share * (full^2 -
 * hub^2))` because tape area, not radius, is proportional to length. Seeking
 * happens on the groove along the bottom of the shell: that is where the tape
 * actually runs behind the plastic, it has both ends, and unlike a reel it can
 * be aimed at a second.
 */
class CassetteWidgetProbe {

    private val title = "Sacrifice"
    private val artist = "Taka feat. めらみぽっぷ"
    private val album = "追憶のサクラメント"
    private val elapsed = "1:47"
    private val total = "4:55"

    private val paper = Color(0xFFEDE6DA)
    private val ink = Color(0xFF1A1714)
    private val inkSoft = Color(0xFF5A5248)
    private val tape = Color(0xFF3B2A20)
    private val tapeRing = Color(0xFF553D30)
    private val pale = Color(0xFFD5CEC4)
    private val paleDim = Color(0xFFA79F95)
    private val hollow = Color(0xFF0B0908)

    private val cover: ImageBitmap by lazy {
        SkImage.makeFromEncoded(File("SAMPLE_PATH_REMOVED").readBytes()).toComposeImageBitmap()
    }

    @Composable
    private fun Key(icon: IconKey, plate: Color, tint: Color, width: Int = 34, glyph: Int = 18) {
        Box(
            Modifier.width(width.dp).height(30.dp).clip(MaterialTheme.shapes.small).background(plate),
            contentAlignment = Alignment.Center,
        ) {
            Symbol(icon, null, tint = tint, fill = 1f, weight = 500, modifier = Modifier.size(glyph.dp))
        }
    }

    @Composable
    private fun Shell(fraction: Float, seeking: Boolean) {
        val c = NxTheme.colors
        val shell = lerp(c.surfaceContainerHigh, Color.Black, 0.52f)
        Canvas(Modifier.fillMaxWidth().height(200.dp).clip(RoundedCornerShape(4.dp))) {
            val d = 2f
            val w = size.width
            val h = size.height
            drawRoundRect(shell, cornerRadius = CornerRadius(4f * d, 4f * d))
            drawLine(
                color = Color.White.copy(alpha = 0.10f),
                start = Offset(4f * d, 1f), end = Offset(w - 4f * d, 1f), strokeWidth = 2f,
            )

            val lx = w / 2f - 0.209f * w
            val rx = w / 2f + 0.209f * w
            val cy = h * 0.67f
            val openR = h * 0.21f
            val hubR = h * 0.055f
            val fullR = openR * 0.95f

            fun radius(share: Float): Float {
                val s = share.coerceIn(0f, 1f)
                return sqrt(hubR * hubR + s * (fullR * fullR - hubR * hubR))
            }

            listOf(lx to radius(1f - fraction), rx to radius(fraction)).forEach { (cx, r) ->
                val hole = Path().apply { addOval(Rect(Offset(cx, cy), openR)) }
                drawPath(hole, hollow)
                clipPath(hole) {
                    drawCircle(tape, radius = r, center = Offset(cx, cy))
                    for (k in 1..5) {
                        drawCircle(
                            color = tapeRing.copy(alpha = 0.5f), radius = hubR + (r - hubR) * (k / 6f),
                            center = Offset(cx, cy), style = Stroke(width = 0.7f),
                        )
                    }
                    drawCircle(pale, radius = hubR, center = Offset(cx, cy))
                    drawCircle(paleDim, radius = hubR * 0.78f, center = Offset(cx, cy))
                    val boreR = hubR * 0.52f
                    drawCircle(hollow, radius = boreR, center = Offset(cx, cy))
                    val teeth = Path()
                    for (k in 0 until 6) {
                        val a = (k * 60f) * (Math.PI / 180f).toFloat()
                        val wide = (22f * Math.PI / 180f).toFloat()
                        val narrow = (15f * Math.PI / 180f).toFloat()
                        val tip = boreR * 0.34f
                        teeth.moveTo(cx + cos(a - wide) * boreR, cy + sin(a - wide) * boreR)
                        teeth.lineTo(cx + cos(a + wide) * boreR, cy + sin(a + wide) * boreR)
                        teeth.lineTo(cx + cos(a + narrow) * tip, cy + sin(a + narrow) * tip)
                        teeth.lineTo(cx + cos(a - narrow) * tip, cy + sin(a - narrow) * tip)
                        teeth.close()
                    }
                    drawPath(teeth, pale)
                }
                drawCircle(
                    color = Color.Black.copy(alpha = 0.55f), radius = openR,
                    center = Offset(cx, cy), style = Stroke(width = 2.5f),
                )
            }

            // The seek groove, on the line the tape runs behind the plastic.
            val gy = h - 18f * d
            val gl = w * 0.09f
            val gr = w * 0.91f
            // The unplayed part has to read as a groove in the plastic, so it is
            // a lit line over a dark one rather than dark on dark.
            drawLine(
                color = Color.Black.copy(alpha = 0.55f), start = Offset(gl, gy + 1f), end = Offset(gr, gy + 1f),
                strokeWidth = if (seeking) 6f else 4f, cap = StrokeCap.Round,
            )
            drawLine(
                color = Color.White.copy(alpha = 0.16f), start = Offset(gl, gy), end = Offset(gr, gy),
                strokeWidth = if (seeking) 5f else 3f, cap = StrokeCap.Round,
            )
            drawLine(
                color = if (seeking) c.primary else c.primary.copy(alpha = 0.55f),
                start = Offset(gl, gy), end = Offset(gl + (gr - gl) * fraction, gy),
                strokeWidth = if (seeking) 5f else 3f, cap = StrokeCap.Round,
            )
            if (seeking) {
                drawCircle(c.primary, radius = 11f, center = Offset(gl + (gr - gl) * fraction, gy))
                drawCircle(hollow, radius = 4f, center = Offset(gl + (gr - gl) * fraction, gy))
            }

            fun recess(centerFraction: Float, widthFraction: Float, hDp: Float) {
                val ww = w * widthFraction
                val hh = hDp * d
                drawRoundRect(
                    color = hollow,
                    topLeft = Offset(w * centerFraction - ww / 2f, h - hh),
                    size = GeomSize(ww, hh + 4f * d),
                    cornerRadius = CornerRadius(3f * d, 3f * d),
                )
            }
            recess(0.50f, 0.21f, 11f)
            recess(0.255f, 0.075f, 8f)
            recess(0.745f, 0.075f, 8f)

            val screw = Color.Black.copy(alpha = 0.5f)
            val inset = 9f * d
            listOf(
                Offset(inset, inset), Offset(w - inset, inset),
                Offset(inset, h - 9f * d), Offset(w - inset, h - 9f * d),
                Offset(w / 2f, cy),
            ).forEach { drawCircle(screw, radius = 2.6f * d, center = it) }
        }
    }

    @Composable
    private fun Widget(fraction: Float, seeking: Boolean, repeatOn: Boolean) {
        val c = NxTheme.colors
        NxSurface(NxSurfaceLevel.Floating, Modifier.width(340.dp), shape = MaterialTheme.shapes.medium) {
            Column(Modifier.fillMaxWidth().padding(Spacing.s12)) {
                Box {
                    Shell(fraction, seeking)
                    Row(
                        Modifier.fillMaxWidth().height(83.dp)
                            .padding(start = 11.dp, end = 11.dp, top = 11.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(paper)
                            .padding(6.dp),
                    ) {
                        Image(
                            cover, null, contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxHeight().aspectRatio(1f).clip(RoundedCornerShape(1.dp)),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Center) {
                            Text(
                                title, style = MaterialTheme.typography.titleMedium, color = ink,
                                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                artist, style = MaterialTheme.typography.labelMedium, color = inkSoft,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "$elapsed / $total", style = MaterialTheme.typography.labelSmall,
                                color = inkSoft, maxLines = 1,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(Spacing.s12))

                val plate = Color.White.copy(alpha = 0.07f)
                // Two keys a side. Equal weights centre the transport between the
                // groups, not on the widget, so unequal sides put play off axis:
                // with 76 dp left against 34 dp right it landed 21 dp adrift of
                // the centre screw and the middle of the seek groove.
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Key(
                        NxIcon.Repeat,
                        if (repeatOn) c.primary.copy(alpha = 0.22f) else plate,
                        if (repeatOn) c.primary else c.textSecondary,
                    )
                    Spacer(Modifier.width(Spacing.s8))
                    Key(NxIcon.Shuffle, plate, c.textSecondary)
                    Spacer(Modifier.weight(1f))
                    Key(NxIcon.SkipPrevious, plate, c.textSecondary)
                    Spacer(Modifier.width(Spacing.s6))
                    Key(NxIcon.Pause, c.primary, c.onPrimary, width = 46, glyph = 20)
                    Spacer(Modifier.width(Spacing.s6))
                    Key(NxIcon.SkipNext, plate, c.textSecondary)
                    Spacer(Modifier.weight(1f))
                    Key(NxIcon.QueueMusic, plate, c.textSecondary)
                    Spacer(Modifier.width(Spacing.s8))
                    Key(NxIcon.MoreVert, plate, c.textSecondary)
                }
            }
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun sheet(name: String, body: @Composable () -> Unit) {
        val d = 2.4f
        val scene = ImageComposeScene((380 * d).toInt(), (320 * d).toInt(), density = Density(d)) {
            NxTheme(useDarkTheme = true) {
                Box(
                    Modifier.fillMaxSize().background(NxTheme.colors.background),
                    contentAlignment = Alignment.Center,
                ) { body() }
            }
        }
        val img = scene.render()
        scene.close()
        File("build/render").mkdirs()
        img.encodeToData(EncodedImageFormat.PNG)?.bytes?.let { File("build/render/final-$name.png").writeBytes(it) }
    }

    @Test
    fun probe() {
        val f = 107f / 295f
        sheet("rest") { Widget(f, seeking = false, repeatOn = true) }
        sheet("seek") { Widget(f, seeking = true, repeatOn = false) }
    }
}
