package hivens.ui.skin3d

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.Density
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertFalse

/**
 * A pose set with motion off appears without anything else happening. The snap
 * moves the animator and leaves the clock alone, and the draw used to read only
 * the clock, so the figure stood in its old pose until a drag or a resize.
 */
class SkinViewSnapRedrawTest {

    private fun skin() = run {
        val rnd = Random(7)
        val bytes = ByteArray(64 * 64 * 4)
        for (i in 0 until 64 * 64) {
            bytes[i * 4] = rnd.nextInt(256).toByte()
            bytes[i * 4 + 1] = rnd.nextInt(256).toByte()
            bytes[i * 4 + 2] = rnd.nextInt(256).toByte()
            bytes[i * 4 + 3] = 0xFF.toByte()
        }
        Image.makeRaster(ImageInfo(64, 64, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL), bytes, 64 * 4)
            .toComposeImageBitmap()
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `a snapped pose is drawn on the next frame`() {
        val state = SkinViewState(initialYaw = 0.5f)
        state.motionMultiplier = 0f
        val texture = skin()
        val scene = ImageComposeScene(160, 240, density = Density(1f)) {
            SkinView3D(texture, Modifier.fillMaxSize(), interactive = false, autoSpin = false, state = state)
        }
        try {
            var t = 0L
            fun render(): ByteArray {
                t += 16_000_000L
                return Bitmap.makeFromImage(scene.render(t)).readPixels()!!
            }
            // The rasterize runs off the frame thread and publishes when it is done,
            // so frames are taken until the picture arrives, and then until it changes.
            fun firstDrawn(): ByteArray {
                repeat(100) {
                    val now = render()
                    if (now.any { it != 0.toByte() }) return now
                    Thread.sleep(20)
                }
                error("the figure was never drawn")
            }
            fun changedFrom(old: ByteArray): ByteArray {
                var now = old
                repeat(100) {
                    now = render()
                    if (!now.contentEquals(old)) return now
                    Thread.sleep(20)
                }
                return now
            }
            // Settle the first picture: a frame or two may still be the empty one.
            var before = firstDrawn()
            repeat(5) { Thread.sleep(20); before = render() }
            state.setPose(Pose(rightArm = PartAngles(roll = -2.7f), leftArm = PartAngles(roll = 2.7f)))
            val after = changedFrom(before)
            assertFalse(before.contentEquals(after), "the figure kept its old pose")
        } finally {
            scene.close()
        }
    }
}
