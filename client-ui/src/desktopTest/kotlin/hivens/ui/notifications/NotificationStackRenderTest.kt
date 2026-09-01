package hivens.ui.notifications

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import hivens.ui.notifications.render.NotificationStack
import hivens.ui.theme.NxTheme
import org.jetbrains.skia.Bitmap
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Off-screen behavioural check of the toast stack. The point is the DISMISSAL:
 * a card must fade out rather than blink away, which is only true because the
 * stack is a keyed lazy list running `animateItem` -- the previous scrolling
 * Column could not animate a removal at all, since a dismissed group is gone
 * from the center and its node left composition on the same frame.
 *
 * Measured as ink (pixels differing from the flat backdrop): two cards paint
 * more than one, and a just-dismissed card is still painting while its exit
 * runs. No display is involved, so this is safe on any session.
 */
class NotificationStackRenderTest {

    private fun push(center: NotificationCenter, key: String, title: String) {
        center.push(
            sourceKey = key,
            sender    = key,
            iconUrl   = null,
            severity  = Severity.Info,
            // Sticky never auto-dismisses, so the stack's age-out effect cannot
            // race the frames this test drives.
            kind      = Kind.Sticky,
            title     = title,
        )
    }

    private fun ink(scene: ImageComposeScene, atNanos: Long): Int {
        val bmp = Bitmap.makeFromImage(scene.render(atNanos))
        var n = 0
        var y = 0
        while (y < bmp.height) {
            var x = 0
            while (x < bmp.width) {
                if (bmp.getColor(x, y) != BACKDROP) n++
                x += 2
            }
            y += 2
        }
        return n
    }

    @Test
    fun `a dismissed toast fades out instead of vanishing on the same frame`() {
        val center = NotificationCenter()
        push(center, "a", "first")
        push(center, "b", "second")

        val scene = ImageComposeScene(width = 900, height = 700, density = Density(1f)) {
            NxTheme(useDarkTheme = true) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    NotificationStack(center = center)
                }
            }
        }
        try {
            var t = 0L
            fun step(frames: Int) { repeat(frames) { scene.render(t); t += FRAME } }

            step(20)
            val inkTwoCards = ink(scene, t)

            center.dismiss("a")
            // One frame for the removal to reach composition, then sample while
            // the exit transition is still on screen.
            step(1)
            val inkMidExit = ink(scene, t)

            step(60)
            val inkOneCard = ink(scene, t)

            assertTrue(inkTwoCards > inkOneCard, "two cards must paint more than one: $inkTwoCards vs $inkOneCard")
            assertTrue(
                inkMidExit > inkOneCard,
                "the dismissed card must still be painting mid-exit: $inkMidExit should exceed the settled $inkOneCard",
            )
        } finally {
            scene.close()
        }
    }

    @Test
    fun `an empty backlog paints nothing and still composes`() {
        val center = NotificationCenter()
        val scene = ImageComposeScene(width = 900, height = 700, density = Density(1f)) {
            NxTheme(useDarkTheme = true) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    NotificationStack(center = center)
                }
            }
        }
        try {
            var t = 0L
            repeat(10) { scene.render(t); t += FRAME }
            // Mounted-but-empty is deliberate (it is what gives the first toast its
            // fade-in), so it must cost nothing on screen.
            assertTrue(ink(scene, t) == 0, "an empty stack must paint no ink")
        } finally {
            scene.close()
        }
    }

    private companion object {
        const val FRAME = 16_000_000L
        /** Opaque black, the scene backdrop, as skia's ARGB int. */
        const val BACKDROP = 0xFF000000.toInt()
    }
}
