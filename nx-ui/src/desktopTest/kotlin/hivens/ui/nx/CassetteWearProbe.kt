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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size as GeomSize
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
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
import kotlin.random.Random
import kotlin.test.Test

/**
 * The tape wears out with every play.
 *
 * A physical cassette does, so this one does too: the widget keeps a play count
 * per file and the drawing degrades against it. Paper yellows, the shell picks
 * up scuffs, the pack loses its sheen, the art desaturates, and past the end of
 * the scale the tape is chewed and the track will not open until it is wound
 * back in. The picture is then put through generations of low quality encoding
 * proportional to the same counter, which is done outside this file.
 *
 * Deliberate, not a bug. Nothing imports it.
 */
class CassetteWearProbe {

    private val title = "Sacrifice"
    private val artist = "Taka feat. めらみぽっぷ"
    private val time = "1:47 / 4:55"
    private val fraction = 107f / 295f

    private val hollow = Color(0xFF0B0908)

    private val cover: ImageBitmap by lazy {
        SkImage.makeFromEncoded(File("SAMPLE_PATH_REMOVED").readBytes()).toComposeImageBitmap()
    }

    private fun radius(share: Float, hubR: Float, fullR: Float): Float {
        val s = share.coerceIn(0f, 1f)
        return sqrt(hubR * hubR + s * (fullR * fullR - hubR * hubR))
    }

    private fun DrawScope.spool(cx: Float, cy: Float, hubR: Float, pale: Color, paleDim: Color) {
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

    @Composable
    private fun Cassette(wear: Float, chewed: Boolean, blank: Boolean = false) {
        val c = NxTheme.colors
        val w0 = wear.coerceIn(0f, 1f)
        val shell = lerp(lerp(c.surfaceContainerHigh, Color.Black, 0.52f), Color(0xFF6B6259), w0 * 0.22f)
        val paper = lerp(Color(0xFFEDE6DA), Color(0xFFB99A5E), w0 * 0.62f)
        val ink = lerp(Color(0xFF1A1714), Color(0xFF6B5B44), w0 * 0.45f)
        val inkSoft = lerp(Color(0xFF5A5248), Color(0xFF8A7B62), w0 * 0.5f)
        val tape = lerp(Color(0xFF3B2A20), Color(0xFF57493F), w0 * 0.55f)
        val tapeRing = lerp(Color(0xFF553D30), Color(0xFF6B5F55), w0 * 0.5f)
        val pale = lerp(Color(0xFFD5CEC4), Color(0xFFA79683), w0 * 0.6f)
        val paleDim = lerp(Color(0xFFA79F95), Color(0xFF8A7A68), w0 * 0.6f)

        Box(Modifier.width(316.dp).height(200.dp)) {
            Canvas(Modifier.fillMaxSize().clip(RoundedCornerShape(4.dp))) {
                val d = 2f
                val w = size.width
                val h = size.height
                drawRoundRect(shell, cornerRadius = CornerRadius(4f * d, 4f * d))
                drawLine(
                    color = Color.White.copy(alpha = 0.10f * (1f - w0 * 0.7f)),
                    start = Offset(4f * d, 1f), end = Offset(w - 4f * d, 1f), strokeWidth = 2f,
                )

                val hubX = 0.209f * w
                val lx = w / 2f - hubX
                val rx = w / 2f + hubX
                val cy = h * 0.62f
                val openR = h * 0.21f
                val hubR = h * 0.055f
                val fullR = openR * 0.95f
                val rL = if (chewed) hubR * 1.15f else radius(1f - fraction, hubR, fullR)
                val rR = if (chewed) hubR * 1.15f else radius(fraction, hubR, fullR)

                listOf(lx to rL, rx to rR).forEach { (cx, r) ->
                    val hole = Path().apply { addOval(Rect(Offset(cx, cy), openR)) }
                    drawPath(hole, hollow)
                    clipPath(hole) {
                        drawCircle(tape, radius = r, center = Offset(cx, cy))
                        for (k in 1..5) {
                            drawCircle(
                                color = tapeRing.copy(alpha = 0.5f * (1f - w0 * 0.6f)),
                                radius = hubR + (r - hubR) * (k / 6f),
                                center = Offset(cx, cy), style = Stroke(width = 0.7f),
                            )
                        }
                        spool(cx, cy, hubR, pale, paleDim)
                    }
                    drawCircle(
                        color = Color.Black.copy(alpha = 0.55f), radius = openR,
                        center = Offset(cx, cy), style = Stroke(width = 2.5f),
                    )
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
                    Offset(w / 2f, cy),
                ).forEach { drawCircle(screw, radius = 2.6f * d, center = it) }

                // Scuffs. Seeded off the wear step so a given count always looks
                // the same, otherwise every recomposition would re-scratch it.
                val rnd = Random((w0 * 1000).toInt())
                repeat((w0 * 34).toInt()) {
                    val x = rnd.nextFloat() * w
                    val y = rnd.nextFloat() * h
                    val len = 6f + rnd.nextFloat() * 34f
                    val ang = (rnd.nextFloat() - 0.5f) * 0.6f
                    drawLine(
                        color = Color.White.copy(alpha = 0.05f + rnd.nextFloat() * 0.07f),
                        start = Offset(x, y),
                        end = Offset(x + cos(ang) * len, y + sin(ang) * len),
                        strokeWidth = 1f, cap = StrokeCap.Round,
                    )
                }

                if (chewed) {
                    // The tape is out of the shell and looped over the bottom edge.
                    val spill = Path()
                    spill.moveTo(w * 0.50f, h - 6f)
                    spill.cubicTo(w * 0.20f, h + 6f, w * 0.16f, h - 34f, w * 0.36f, h - 20f)
                    spill.cubicTo(w * 0.56f, h - 6f, w * 0.30f, h + 10f, w * 0.62f, h - 4f)
                    spill.cubicTo(w * 0.86f, h - 18f, w * 0.74f, h - 44f, w * 0.58f, h - 30f)
                    drawPath(spill, tape, style = Stroke(width = 2.5f))
                }
            }

            Row(
                Modifier.fillMaxWidth().height(91.dp)
                    .padding(start = 11.dp, end = 11.dp, top = 11.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(paper)
                    .padding(6.dp),
            ) {
                if (blank) {
                    Canvas(Modifier.fillMaxSize()) {
                        for (k in 1..4) {
                            val y = size.height * k / 5f
                            drawLine(
                                color = inkSoft.copy(alpha = 0.35f),
                                start = Offset(4f, y), end = Offset(size.width - 4f, y),
                                strokeWidth = 1f,
                            )
                        }
                    }
                    return@Row
                }
                Image(
                    cover, null, contentScale = ContentScale.Crop,
                    colorFilter = ColorFilter.colorMatrix(
                        ColorMatrix().apply { setToSaturation(1f - w0 * 0.75f) },
                    ),
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
                    Text(
                        if (chewed) "лента зажёвана" else time,
                        style = MaterialTheme.typography.labelSmall, color = inkSoft, maxLines = 1,
                    )
                }
            }
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun one(name: String, wear: Float, chewed: Boolean) {
        val d = 2f
        val scene = ImageComposeScene((330 * d).toInt(), (214 * d).toInt(), density = Density(d)) {
            NxTheme(useDarkTheme = true) {
                Box(
                    Modifier.fillMaxSize().background(NxTheme.colors.background),
                    contentAlignment = Alignment.Center,
                ) { Cassette(wear, chewed) }
            }
        }
        val img = scene.render()
        scene.close()
        File("build/render").mkdirs()
        img.encodeToData(EncodedImageFormat.PNG)?.bytes?.let { File("build/render/wear-$name.png").writeBytes(it) }
    }

    /**
     * Nothing loaded. The slot is not empty, the tape is just lying there with
     * its guts out, which is a more honest empty state than a grey rectangle and
     * costs the same to draw.
     */
    @Composable
    private fun Sprawled() {
        val c = NxTheme.colors
        NxSurface(NxSurfaceLevel.Floating, Modifier.width(340.dp), shape = MaterialTheme.shapes.medium) {
            Box(
                Modifier.fillMaxWidth().height(248.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier.graphicsLayer(
                        rotationZ = -13f, scaleX = 0.74f, scaleY = 0.74f, translationY = -14f,
                    ),
                ) {
                    Cassette(0.55f, chewed = true, blank = true)
                }
                Canvas(Modifier.fillMaxSize()) {
                    // The tape it spat out, over the card and over the shell both.
                    val loops = Path()
                    loops.moveTo(size.width * 0.30f, size.height * 0.63f)
                    loops.cubicTo(
                        size.width * 0.06f, size.height * 0.80f,
                        size.width * 0.30f, size.height * 0.94f,
                        size.width * 0.46f, size.height * 0.78f,
                    )
                    loops.cubicTo(
                        size.width * 0.60f, size.height * 0.64f,
                        size.width * 0.72f, size.height * 0.93f,
                        size.width * 0.90f, size.height * 0.80f,
                    )
                    drawPath(loops, Color(0xFF3B2A20), style = Stroke(width = 2.5f))
                }
                ChewedHint(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = Spacing.s8),
                )
            }
        }
    }

    /**
     * The hint the widget fails to finish. The held vowel is laid out a character
     * at a time so each one is wider and thinner than the last, which is what
     * stretched tape does, and then the machine eats it.
     */
    @Composable
    private fun ChewedHint(modifier: Modifier) {
        val c = NxTheme.colors
        val style = MaterialTheme.typography.labelMedium
        Row(modifier, verticalAlignment = Alignment.Bottom) {
            Text("пере-пер", style = style, color = c.textSecondary)
            "еееее".forEachIndexed { i, ch ->
                Text(
                    ch.toString(), style = style, color = c.textSecondary,
                    modifier = Modifier.graphicsLayer(
                        scaleX = 1f + i * 0.30f,
                        scaleY = 1f - i * 0.08f,
                        translationY = i * 0.7f,
                    ),
                )
                // A wider glyph still occupies its original slot, so the run has
                // to be spaced by hand or the vowels pile up into a blot.
                Spacer(Modifier.width((i * 1.9f).dp))
            }
            Spacer(Modifier.width(6.dp))
            Text(
                "*жямк*", style = style, color = Color(0xFF6B4A38),
                fontWeight = FontWeight.SemiBold,
            )
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun sprawledSheet() {
        val d = 2f
        val scene = ImageComposeScene((372 * d).toInt(), (282 * d).toInt(), density = Density(d)) {
            NxTheme(useDarkTheme = true) {
                Box(
                    Modifier.fillMaxSize().background(NxTheme.colors.background),
                    contentAlignment = Alignment.Center,
                ) { Sprawled() }
            }
        }
        val img = scene.render()
        scene.close()
        img.encodeToData(EncodedImageFormat.PNG)?.bytes?.let { File("build/render/wear-idle.png").writeBytes(it) }
    }

    @Test
    fun probe() {
        one("0", 0.00f, false)
        one("1", 0.20f, false)
        one("2", 0.45f, false)
        one("3", 0.70f, false)
        one("4", 1.00f, false)
        one("5", 1.00f, true)
        sprawledSheet()
    }
}
