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
import androidx.compose.ui.geometry.Size as GeomSize
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hivens.ui.theme.NxTheme
import hivens.ui.theme.Spacing
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test

/** How the paper on the face of the shell is laid out. */
enum class LabelKind(val caption: String) {
    Paper("A1. бумага во всю ширину"),
    Banded("A2. цветная шапка"),
    Cover("A3. обложка в поле"),
    Inset("A4. уже корпуса, поля пластика"),
}

/** What the tape is seen through. */
enum class WindowKind(val caption: String, val widthFraction: Float, val heightDp: Float) {
    Slot("B1. слот 0.62", 0.62f, 60f),
    Wide("B2. слот 0.86", 0.86f, 62f),
    Full("B3. во всю ширину", 0.94f, 64f),
    Twin("B4. два круглых окна", 0.62f, 60f),
}

/** The plastic itself. */
enum class ShellKind(val caption: String, val corner: Float) {
    Plain("C1. ровный, скругление 8", 8f),
    Sharp("C2. скругление 4, фаска", 4f),
    Panel("C3. приподнятая площадка", 4f),
    Pinched("C4. ступенчатый низ", 4f),
}

data class Look(
    val label: LabelKind = LabelKind.Paper,
    val window: WindowKind = WindowKind.Slot,
    val shell: ShellKind = ShellKind.Plain,
)

/**
 * The cassette taken apart, one component at a time.
 *
 * Judging the whole drawing at once kept mixing verdicts about the shell with
 * verdicts about the label, so each sheet holds one component under four
 * versions and freezes everything else. Hub centres stay 42 mm apart in every
 * version, measured off the body and not off the window, so widening the window
 * moves the glass and never the mechanism.
 */
class CassettePartsProbe {

    private val title = "Sacrifice"
    private val artist = "Taka feat. めらみぽっぷ"
    private val album = "追憶のサクラメント"
    private val time = "1:47 / 4:55"
    private val fraction = 107f / 295f

    private val bodyW = 316f
    private val bodyH = 200f

    private val paper = Color(0xFFEDE6DA)
    private val ink = Color(0xFF1A1714)
    private val inkSoft = Color(0xFF5A5248)
    private val tape = Color(0xFF3B2A20)
    private val tapeRing = Color(0xFF553D30)
    private val plastic = Color(0xFF7C746A)
    private val plasticLit = Color(0xFF9A9186)
    private val plasticDim = Color(0xFF5C554D)
    private val hollow = Color(0xFF0B0908)

    private val cover: ImageBitmap by lazy {
        ProbeSample.cover()
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
        img.encodeToData(EncodedImageFormat.PNG)?.bytes?.let { File("build/render/parts-$name.png").writeBytes(it) }
    }

    @Composable
    private fun Label(kind: LabelKind) {
        val c = NxTheme.colors
        val sideInset = if (kind == LabelKind.Inset) 26.dp else 11.dp
        val h = if (kind == LabelKind.Inset) 86.dp else 93.dp
        val shape = RoundedCornerShape(2.dp)
        Box(Modifier.fillMaxWidth().height(h + 11.dp).padding(start = sideInset, end = sideInset, top = 11.dp)) {
            when (kind) {
                LabelKind.Cover -> Box(Modifier.fillMaxSize().clip(shape)) {
                    Image(cover, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    Box(
                        Modifier.fillMaxSize().background(
                            Brush.verticalGradient(0.3f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f)),
                        ),
                    )
                    Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 10.dp, vertical = 8.dp)) {
                        Text(
                            title, style = MaterialTheme.typography.titleMedium, color = Color.White,
                            fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            "$artist  ·  $time", style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                LabelKind.Banded -> Row(Modifier.fillMaxSize().clip(shape).background(paper).padding(6.dp)) {
                    Image(
                        cover, null, contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxHeight().aspectRatio(1f).clip(RoundedCornerShape(1.dp)),
                    )
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        Box(
                            Modifier.fillMaxWidth().height(26.dp).background(c.primary)
                                .padding(horizontal = 8.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Text(
                                title, style = MaterialTheme.typography.titleSmall, color = c.onPrimary,
                                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            artist, style = MaterialTheme.typography.labelMedium, color = ink,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.weight(1f))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                album, style = MaterialTheme.typography.labelSmall, color = inkSoft,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(time, style = MaterialTheme.typography.labelSmall, color = inkSoft, maxLines = 1)
                        }
                    }
                }
                else -> Row(Modifier.fillMaxSize().clip(shape).background(paper).padding(6.dp)) {
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
                        Spacer(Modifier.height(6.dp))
                        Box(Modifier.width(28.dp).height(2.dp).background(c.primary))
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                album, style = MaterialTheme.typography.labelSmall, color = inkSoft,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, false),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(time, style = MaterialTheme.typography.labelSmall, color = inkSoft, maxLines = 1)
                        }
                    }
                }
            }
        }
    }

    /** Hub centres are 42 mm apart on a 100.5 mm body, wherever the glass ends. */
    private fun hubOffsetOf(kind: WindowKind) = 0.209f / kind.widthFraction

    @Composable
    private fun Glass(kind: WindowKind, modifier: Modifier) {
        val twin = kind == WindowKind.Twin
        Canvas(modifier.clip(RoundedCornerShape(4.dp))) {
            val cy = size.height * 0.43f
            val hubR = size.height * 0.14f
            val fullR = size.height * 0.40f
            val off = hubOffsetOf(kind) * size.width
            val lx = size.width / 2f - off
            val rx = size.width / 2f + off
            val runY = size.height * 0.86f
            val rollR = size.height * 0.07f
            val lrx = size.width * 0.06f
            val rrx = size.width * 0.94f

            fun radius(share: Float): Float {
                val s = share.coerceIn(0f, 1f)
                return sqrt(hubR * hubR + s * (fullR * fullR - hubR * hubR))
            }
            val rL = radius(1f - fraction)
            val rR = radius(fraction)

            if (twin) {
                drawCircle(hollow, radius = fullR * 1.24f, center = Offset(lx, cy))
                drawCircle(hollow, radius = fullR * 1.24f, center = Offset(rx, cy))
            } else {
                drawRoundRect(hollow, cornerRadius = CornerRadius(4f * 2f, 4f * 2f))
            }

            if (!twin) {
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
                    drawCircle(plastic, radius = rollR, center = Offset(it, runY))
                    drawCircle(hollow, radius = rollR * 0.38f, center = Offset(it, runY))
                }
            }

            fun hub(cx: Float) {
                drawCircle(plastic, radius = hubR, center = Offset(cx, cy))
                drawCircle(
                    color = plasticLit.copy(alpha = 0.55f), radius = hubR,
                    center = Offset(cx, cy), style = Stroke(width = 0.9f),
                )
                drawCircle(plasticDim, radius = hubR * 0.78f, center = Offset(cx, cy))
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
                drawPath(teeth, plasticLit)
            }

            fun pack(cx: Float, r: Float) {
                drawCircle(tape, radius = r, center = Offset(cx, cy))
                for (k in 1..5) {
                    drawCircle(
                        color = tapeRing.copy(alpha = 0.5f), radius = hubR + (r - hubR) * (k / 6f),
                        center = Offset(cx, cy), style = Stroke(width = 0.7f),
                    )
                }
                drawCircle(
                    color = Color.Black.copy(alpha = 0.45f), radius = r,
                    center = Offset(cx, cy), style = Stroke(width = 0.9f),
                )
                hub(cx)
            }
            pack(lx, rL)
            pack(rx, rR)
        }
    }

    @Composable
    private fun Cassette(look: Look) {
        val c = NxTheme.colors
        val shell = lerp(c.surfaceContainerHigh, Color.Black, 0.45f)
        Box(Modifier.width(bodyW.dp).height(bodyH.dp)) {
            Canvas(Modifier.fillMaxSize().clip(RoundedCornerShape(look.shell.corner.dp))) {
                val d = 2f
                drawRoundRect(shell, cornerRadius = CornerRadius(look.shell.corner * d, look.shell.corner * d))

                when (look.shell) {
                    ShellKind.Sharp -> {
                        val edge = look.shell.corner * d
                        drawLine(
                            color = Color.White.copy(alpha = 0.10f),
                            start = Offset(edge, 1f), end = Offset(size.width - edge, 1f), strokeWidth = 2f,
                        )
                        drawLine(
                            color = Color.Black.copy(alpha = 0.4f),
                            start = Offset(edge, size.height - 1f),
                            end = Offset(size.width - edge, size.height - 1f), strokeWidth = 2f,
                        )
                    }
                    ShellKind.Panel -> {
                        drawRoundRect(
                            color = lerp(shell, Color.White, 0.05f),
                            topLeft = Offset(size.width * 0.10f, 100f * d),
                            size = GeomSize(size.width * 0.80f, 82f * d),
                            cornerRadius = CornerRadius(4f * d, 4f * d),
                        )
                    }
                    ShellKind.Pinched -> {
                        drawRoundRect(
                            color = lerp(shell, Color.White, 0.07f),
                            topLeft = Offset(8f * d, size.height - 26f * d),
                            size = GeomSize(size.width - 16f * d, 26f * d),
                            cornerRadius = CornerRadius(3f * d, 3f * d),
                        )
                    }
                    else -> Unit
                }

                fun recess(centerFraction: Float, widthFraction: Float, hDp: Float) {
                    val w = size.width * widthFraction
                    val h = hDp * d
                    drawRoundRect(
                        color = hollow,
                        topLeft = Offset(size.width * centerFraction - w / 2f, size.height - h),
                        size = GeomSize(w, h + 4f * d),
                        cornerRadius = CornerRadius(3f * d, 3f * d),
                    )
                }
                recess(0.50f, 0.21f, 11f)
                recess(0.255f, 0.075f, 8f)
                recess(0.745f, 0.075f, 8f)

                val screw = Color.Black.copy(alpha = 0.5f)
                val inset = 9f * d
                listOf(
                    Offset(inset, inset), Offset(size.width - inset, inset),
                    Offset(inset, size.height - 16f * d), Offset(size.width - inset, size.height - 16f * d),
                    Offset(size.width / 2f, 107f * d),
                ).forEach { drawCircle(screw, radius = 2.6f * d, center = it) }
            }

            Label(look.label)

            Glass(
                look.window,
                Modifier.align(Alignment.TopCenter)
                    .fillMaxWidth(look.window.widthFraction)
                    .padding(top = 110.dp)
                    .height(look.window.heightDp.dp),
            )
        }
    }

    @Composable
    private fun Grid(cells: List<Pair<String, Look>>) {
        val c = NxTheme.colors
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s20)) {
            cells.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s20)) {
                    row.forEach { (caption, look) ->
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Cassette(look)
                            Spacer(Modifier.height(Spacing.s8))
                            Text(
                                caption, style = MaterialTheme.typography.labelMedium,
                                color = c.textSecondary,
                            )
                        }
                    }
                }
            }
        }
    }

    @Test
    fun probe() {
        sheet("labels", 720, 520) {
            Grid(LabelKind.entries.map { it.caption to Look(label = it) })
        }
        sheet("windows", 720, 520) {
            Grid(WindowKind.entries.map { it.caption to Look(window = it) })
        }
        sheet("shells", 720, 520) {
            Grid(ShellKind.entries.map { it.caption to Look(shell = it) })
        }
        sheet("combo", 720, 520) {
            Grid(
                listOf(
                    "A4 + B2 + C2" to Look(LabelKind.Inset, WindowKind.Wide, ShellKind.Sharp),
                    "A3 + B2 + C2" to Look(LabelKind.Cover, WindowKind.Wide, ShellKind.Sharp),
                    "A1 + B2 + C2" to Look(LabelKind.Paper, WindowKind.Wide, ShellKind.Sharp),
                    "A1 + B1 + C1 (сейчас)" to Look(LabelKind.Paper, WindowKind.Slot, ShellKind.Plain),
                ),
            )
        }
    }
}
