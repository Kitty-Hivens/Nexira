package hivens.ui.effects

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import hivens.ui.theme.NxTheme
import org.jetbrains.skia.Bitmap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A field is a rule from a mote's number and a moment to a place, so the same
 * question always gets the same answer, every mote stays inside the area it was
 * given, and the field is never thicker than its density says.
 */
class ParticleFieldTest {

    @Test
    fun `a mote is the same mote on every frame and every run`() {
        for (field in ParticleField.entries) {
            assertEquals(moteAt(field, 17, 3.5f, 800f, 600f), moteAt(field, 17, 3.5f, 800f, 600f))
        }
    }

    @Test
    fun `motes stay inside the area however long the field runs`() {
        for (field in ParticleField.entries) {
            for (i in 0 until 200) {
                for (t in listOf(0f, 1.7f, 60f, 3_600f)) {
                    val m = moteAt(field, i, t, 800f, 600f)
                    assertTrue(m.x in 0f..800f && m.y in 0f..600f, "$field mote $i left the area at $t: $m")
                    assertTrue(m.alpha in 0f..1f, "$field mote $i has alpha ${m.alpha}")
                }
            }
        }
    }

    @Test
    fun `moving fields move and the stars stay put`() {
        for (field in listOf(ParticleField.Dust, ParticleField.Embers, ParticleField.Snow)) {
            val a = moteAt(field, 5, 0f, 800f, 600f)
            val b = moteAt(field, 5, 2f, 800f, 600f)
            assertTrue(a.x != b.x || a.y != b.y, "$field did not move")
        }
        val s0 = moteAt(ParticleField.Stars, 5, 0f, 800f, 600f)
        val s1 = moteAt(ParticleField.Stars, 5, 2f, 800f, 600f)
        assertEquals(s0.x to s0.y, s1.x to s1.y)
    }

    @Test
    fun `density sets the count and the ceiling holds it`() {
        assertTrue(moteCount(1500f, 900f, ParticleDensity.Sparse) < moteCount(1500f, 900f, ParticleDensity.Rich))
        assertEquals(MAX_MOTES, moteCount(10_000f, 10_000f, ParticleDensity.Rich))
        assertEquals(0, moteCount(0f, 900f, ParticleDensity.Even))
    }

    @Test
    fun `the hash spreads over the whole unit range`() {
        val values = (0 until 2_000).map { rand(it, 1) }
        assertTrue(values.all { it >= 0f && it < 1f })
        assertTrue(values.min() < 0.01f && values.max() > 0.99f)
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun `a field draws over the ground it is given`() {
        val ground = Color.Black.toArgb()
        val scene = ImageComposeScene(400, 300, density = Density(1f)) {
            NxTheme(dark = true) {
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    NxParticleField(ParticleField.Snow, Modifier.fillMaxSize(), ParticleDensity.Rich)
                }
            }
        }
        try {
            var t = 0L
            repeat(4) { scene.render(t).close(); t += 16_000_000L }
            val bmp = Bitmap.makeFromImage(scene.render(t))
            var lit = 0
            for (x in 0 until 400 step 2) for (y in 0 until 300 step 2) if (bmp.getColor(x, y) != ground) lit++
            assertTrue(lit > 20, "the field drew $lit lit samples")
        } finally {
            scene.close()
        }
    }
}
