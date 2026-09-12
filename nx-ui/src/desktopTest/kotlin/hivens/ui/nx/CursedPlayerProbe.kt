package hivens.ui.nx

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hivens.ui.icons.NxIcon
import hivens.ui.icons.Symbol
import hivens.ui.theme.NxTheme
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image as SkImage
import java.io.File
import kotlin.test.Test

/**
 * The deliberately terrible one, on request.
 *
 * Every rule this project has is broken here on purpose and exactly once, so the
 * result is bad by construction rather than by accident: hardcoded colours where
 * tokens exist, hand typed paddings off the Spacing ladder, four corner radii in
 * one card, glyphs scaled non uniformly, text clipped mid character instead of
 * ellipsised, the tape drawn as two flat circles on a wire, and album art
 * resampled to 14 px and blown back up with filtering off. The picture is then
 * put through several generations of low quality JPEG, which is the part that
 * actually carries the reference.
 *
 * It is not wired to anything and nothing imports it.
 */
class CursedPlayerProbe {

    private val cover: ImageBitmap by lazy {
        val full = ProbeSample.coverImage()
        // Resampled to nothing on purpose, then stretched back over the card.
        SkImage.makeFromBitmap(
            org.jetbrains.skia.Bitmap().apply {
                allocN32Pixels(14, 14)
                full.scalePixels(peekPixels()!!, org.jetbrains.skia.SamplingMode.DEFAULT, false)
            },
        ).toComposeImageBitmap()
    }

    @Composable
    private fun Cursed() {
        Box(
            Modifier
                .width(233.dp)
                .height(191.dp)
                .shadow(11.dp)
                .background(
                    Brush.linearGradient(
                        listOf(Color(0xFF6A00FF), Color(0xFF00FF2A), Color(0xFFFF0000)),
                    ),
                )
                .border(3.dp, Color(0xFFFFFF00))
                .padding(7.dp),
        ) {
            Column(Modifier.fillMaxSize()) {
                Row {
                    Image(
                        cover, null, filterQuality = FilterQuality.None,
                        modifier = Modifier.size(54.dp).clip(RoundedCornerShape(0.dp, 17.dp, 3.dp, 9.dp)),
                    )
                    Spacer(Modifier.width(3.dp))
                    Column(Modifier.padding(top = 13.dp)) {
                        Text(
                            "Sacrifice", color = Color(0xFF00FF2A), fontSize = 19.sp,
                            fontWeight = FontWeight.Black, maxLines = 1, overflow = TextOverflow.Clip,
                            softWrap = false,
                            modifier = Modifier.graphicsLayer(scaleX = 1.7f, scaleY = 0.62f)
                                .offset(x = 21.dp),
                        )
                        Text(
                            "Taka feat. めらみぽっぷ", color = Color(0xFFFF0000), fontSize = 13.sp,
                            maxLines = 1, overflow = TextOverflow.Clip, softWrap = false,
                            modifier = Modifier.width(88.dp),
                        )
                        Text(
                            "1:47", color = Color(0xFFFFFFFF), fontSize = 9.sp,
                            modifier = Modifier.offset(x = (-6).dp, y = 4.dp),
                        )
                    }
                }

                Spacer(Modifier.height(3.dp))

                // The reels, restored to the version that was correctly rejected.
                Canvas(Modifier.fillMaxWidth().height(38.dp).background(Color(0xFF202020))) {
                    val cy = size.height / 2f
                    drawLine(Color(0xFF808080), Offset(30f, cy), Offset(size.width - 30f, cy), strokeWidth = 2f)
                    drawCircle(Color(0xFF9E9E9E), radius = 21f, center = Offset(30f, cy))
                    drawCircle(Color(0xFF202020), radius = 9f, center = Offset(30f, cy))
                    drawCircle(Color(0xFFBA68C8), radius = 15f, center = Offset(size.width - 30f, cy))
                    drawCircle(Color(0xFF202020), radius = 9f, center = Offset(size.width - 30f, cy))
                }

                Spacer(Modifier.height(6.dp))

                Box(
                    Modifier.fillMaxWidth().height(9.dp)
                        .background(Color(0xFF00FF2A))
                        .border(2.dp, Color(0xFFFF0000)),
                ) {
                    Box(Modifier.fillMaxWidth(0.36f).fillMaxSize().background(Color(0xFF0000FF)))
                }

                Spacer(Modifier.height(5.dp))

                Row(verticalAlignment = Alignment.Bottom) {
                    Symbol(
                        NxIcon.SkipPrevious, null, tint = Color(0xFFFFFF00),
                        modifier = Modifier.size(27.dp).graphicsLayer(scaleX = 0.58f, scaleY = 1.35f),
                    )
                    Spacer(Modifier.width(19.dp))
                    Symbol(
                        NxIcon.PlayArrow, null, tint = Color(0xFF00FF2A), fill = 0f,
                        modifier = Modifier.size(31.dp).graphicsLayer(scaleX = 1.4f, scaleY = 0.71f),
                    )
                    Spacer(Modifier.width(3.dp))
                    Symbol(
                        NxIcon.SkipNext, null, tint = Color(0xFFFF0000),
                        modifier = Modifier.size(22.dp).offset(y = (-7).dp),
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        "4:55", color = Color(0xFF6A00FF), fontSize = 15.sp,
                        modifier = Modifier.graphicsLayer(scaleY = 1.8f),
                    )
                }
            }
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun probe() {
        // No background: the card is composited over the source footage, and the
        // codec then has to pay for both at 374 kbit.
        val scene = ImageComposeScene(362, 297, density = Density(1.55f)) {
            NxTheme(useDarkTheme = true) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Cursed() }
            }
        }
        val img = scene.render()
        scene.close()
        File("build/render").mkdirs()
        img.encodeToData(EncodedImageFormat.PNG)?.bytes?.let {
            File("build/render/cursed-clean.png").writeBytes(it)
        }
    }
}
