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
import androidx.compose.ui.geometry.Size as GeomSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hivens.ui.theme.NxTheme
import hivens.ui.theme.Spacing
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image as SkImage
import java.io.File
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test

/** What the face of the shell exposes. */
enum class Face(val caption: String) {
    Slot("1. прямоугольное окно (было)"),
    Holes("2. два круглых отверстия"),
    HolesSlot("3. отверстия и смотровая щель"),
    Clear("4. прозрачный корпус"),
}

/**
 * The face of the shell, corrected against the real object.
 *
 * The earlier round compared four kinds of rectangular glass, and the reference
 * photographs say the common compact cassette has none: the hubs are exposed
 * through two large round openings, roughly 27 mm across on a 63.8 mm body, and
 * the hubs themselves are pale. That is also what separates the shape from a
 * floppy, which has a shutter and no wheels of any kind on its face.
 */
class CassetteFaceProbe {

    private val title = "Sacrifice"
    private val artist = "Taka feat. めらみぽっぷ"
    private val time = "1:47 / 4:55"
    private val fraction = 107f / 295f

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

    @OptIn(ExperimentalComposeUiApi::class)
    private fun sheet(name: String, wDp: Int, hDp: Int, body: @Composable () -> Unit) {
        val d = 2f
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
        img.encodeToData(EncodedImageFormat.PNG)?.bytes?.let { File("build/render/face-$name.png").writeBytes(it) }
    }

    private fun radius(share: Float, hubR: Float, fullR: Float): Float {
        val s = share.coerceIn(0f, 1f)
        return sqrt(hubR * hubR + s * (fullR * fullR - hubR * hubR))
    }

    private fun DrawScope.spool(cx: Float, cy: Float, hubR: Float) {
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

    private fun DrawScope.pack(cx: Float, cy: Float, r: Float, hubR: Float) {
        drawCircle(tape, radius = r, center = Offset(cx, cy))
        for (k in 1..5) {
            drawCircle(
                color = tapeRing.copy(alpha = 0.5f), radius = hubR + (r - hubR) * (k / 6f),
                center = Offset(cx, cy), style = Stroke(width = 0.7f),
            )
        }
    }

    private fun DrawScope.tapeRun(lx: Float, rx: Float, cy: Float, rL: Float, rR: Float, lrx: Float, rrx: Float, runY: Float) {
        fun tangent(cx: Float, r: Float, px: Float, py: Float): Offset {
            val d = hypot(px - cx, py - cy)
            val base = atan2(py - cy, px - cx)
            val alpha = acos((r / d).coerceIn(-1f, 1f))
            val p1 = Offset(cx + cos(base + alpha) * r, cy + sin(base + alpha) * r)
            val p2 = Offset(cx + cos(base - alpha) * r, cy + sin(base - alpha) * r)
            val toward = px - cx
            return if ((p1.x - cx) * toward > (p2.x - cx) * toward) p1 else p2
        }
        drawLine(tape, tangent(lx, rL, lrx, runY), Offset(lrx, runY), strokeWidth = 2f)
        drawLine(tape, Offset(lrx, runY), Offset(rrx, runY), strokeWidth = 2f)
        drawLine(tape, Offset(rrx, runY), tangent(rx, rR, rrx, runY), strokeWidth = 2f)
        listOf(lrx, rrx).forEach {
            drawCircle(pale, radius = 5f, center = Offset(it, runY))
            drawCircle(hollow, radius = 2f, center = Offset(it, runY))
        }
    }

    @Composable
    private fun Cassette(face: Face) {
        val c = NxTheme.colors
        val opaque = lerp(c.surfaceContainerHigh, Color.Black, 0.52f)
        val clear = lerp(c.surfaceContainerHigh, Color.White, 0.10f).copy(alpha = 0.55f)
        val shell = if (face == Face.Clear) clear else opaque
        val labelH = if (face == Face.Slot) 93f else 80f

        Box(Modifier.width(316.dp).height(200.dp)) {
            Canvas(Modifier.fillMaxSize().clip(RoundedCornerShape(4.dp))) {
                val d = 2f
                val w = size.width
                val h = size.height
                drawRoundRect(hollow, cornerRadius = CornerRadius(4f * d, 4f * d))
                drawRoundRect(shell, cornerRadius = CornerRadius(4f * d, 4f * d))
                drawLine(
                    color = Color.White.copy(alpha = 0.10f),
                    start = Offset(4f * d, 1f), end = Offset(w - 4f * d, 1f), strokeWidth = 2f,
                )

                val hubX = 0.209f * w
                val lx = w / 2f - hubX
                val rx = w / 2f + hubX

                if (face == Face.Slot) {
                    val top = 110f * d
                    val hgt = 60f * d
                    val glassW = 0.62f * w
                    val cy = top + hgt * 0.43f
                    val hubR = hgt * 0.14f
                    val fullR = hgt * 0.40f
                    val rL = radius(1f - fraction, hubR, fullR)
                    val rR = radius(fraction, hubR, fullR)
                    val glass = Path().apply {
                        addRoundRect(
                            androidx.compose.ui.geometry.RoundRect(
                                (w - glassW) / 2f, top, (w + glassW) / 2f, top + hgt,
                                CornerRadius(4f * d, 4f * d),
                            ),
                        )
                    }
                    drawPath(glass, hollow)
                    clipPath(glass) {
                        tapeRun(
                            lx, rx, cy, rL, rR,
                            (w - glassW) / 2f + glassW * 0.06f,
                            (w + glassW) / 2f - glassW * 0.06f,
                            top + hgt * 0.86f,
                        )
                        pack(lx, cy, rL, hubR)
                        pack(rx, cy, rR, hubR)
                        spool(lx, cy, hubR)
                        spool(rx, cy, hubR)
                    }
                } else {
                    // Two openings of 27 mm on a 63.8 mm body, centres 42 mm apart.
                    val cy = h * 0.62f
                    val openR = h * 0.21f
                    val hubR = h * 0.055f
                    val fullR = openR * 0.95f
                    val rL = radius(1f - fraction, hubR, fullR)
                    val rR = radius(fraction, hubR, fullR)

                    if (face == Face.Clear) {
                        tapeRun(lx, rx, cy, rL, rR, w * 0.09f, w * 0.91f, cy + openR * 1.12f)
                        pack(lx, cy, rL, hubR)
                        pack(rx, cy, rR, hubR)
                        spool(lx, cy, hubR)
                        spool(rx, cy, hubR)
                        listOf(lx, rx).forEach {
                            drawCircle(
                                color = Color.White.copy(alpha = 0.22f), radius = openR,
                                center = Offset(it, cy), style = Stroke(width = 2f),
                            )
                        }
                    } else {
                        if (face == Face.HolesSlot) {
                            val slitH = h * 0.055f
                            drawRoundRect(
                                color = hollow,
                                topLeft = Offset(lx, cy - slitH / 2f),
                                size = GeomSize(rx - lx, slitH),
                                cornerRadius = CornerRadius(2f * d, 2f * d),
                            )
                            val slit = Path().apply {
                                addRoundRect(
                                    androidx.compose.ui.geometry.RoundRect(
                                        lx, cy - slitH / 2f, rx, cy + slitH / 2f,
                                        CornerRadius(2f * d, 2f * d),
                                    ),
                                )
                            }
                            clipPath(slit) {
                                pack(lx, cy, rL, hubR)
                                pack(rx, cy, rR, hubR)
                                drawRect(
                                    tape, topLeft = Offset(lx, cy - 1f),
                                    size = GeomSize(rx - lx, 2f),
                                )
                            }
                        }
                        listOf(lx to rL, rx to rR).forEach { (cx, r) ->
                            val hole = Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(cx, cy), openR)) }
                            drawPath(hole, hollow)
                            clipPath(hole) {
                                pack(cx, cy, r, hubR)
                                spool(cx, cy, hubR)
                            }
                            drawCircle(
                                color = Color.Black.copy(alpha = 0.55f), radius = openR,
                                center = Offset(cx, cy), style = Stroke(width = 2.5f),
                            )
                            drawCircle(
                                color = Color.White.copy(alpha = 0.10f), radius = openR + 1.5f,
                                center = Offset(cx, cy), style = Stroke(width = 1.5f),
                            )
                        }
                    }
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
                    Offset(inset, h - 16f * d), Offset(w - inset, h - 16f * d),
                    Offset(w / 2f, h * 0.62f),
                ).forEach { drawCircle(screw, radius = 2.6f * d, center = it) }
            }

            Row(
                Modifier.fillMaxWidth().height(labelH.dp + 11.dp)
                    .padding(start = 11.dp, end = 11.dp, top = 11.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (face == Face.Clear) paper.copy(alpha = 0.88f) else paper)
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
                    Spacer(Modifier.height(5.dp))
                    Text(time, style = MaterialTheme.typography.labelSmall, color = inkSoft, maxLines = 1)
                }
            }
        }
    }

    @Test
    fun probe() {
        sheet("faces", 720, 520) {
            val c = NxTheme.colors
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s20)) {
                Face.entries.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s20)) {
                        row.forEach { face ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Cassette(face)
                                Spacer(Modifier.height(Spacing.s8))
                                Text(
                                    face.caption, style = MaterialTheme.typography.labelMedium,
                                    color = c.textSecondary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
