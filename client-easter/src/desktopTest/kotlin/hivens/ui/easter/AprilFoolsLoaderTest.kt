package hivens.ui.easter

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The SPI registration is one resource file, and nothing but this test fails
 * when it is missing -- the loader falls back to [NoOpAprilFools] and every
 * caller keeps working, which is how the engine spent releases resolving to a
 * no-op while its own documentation described a build gate that was never
 * built.
 */
class AprilFoolsLoaderTest {

    @Test
    fun `the engine is the resolved implementation`() {
        assertIs<RealAprilFools>(AprilFoolsLoader.instance)
        assertTrue(AprilFoolsLoader.instance.providesDebugPanel, "the debug panel unlock has something to unlock")
    }

    @Test
    fun `the calendar is the only thing that turns it on`() {
        val af = AprilFoolsLoader.instance
        val previous = af.debugForceActive
        try {
            af.debugForceActive = null
            val inSeason = LocalDate.now().let { it.monthValue == 4 && it.dayOfMonth <= 14 }
            assertEquals(inSeason, af.isActive(), "active exactly during April 1-14")
            if (!inSeason) assertEquals(0f, af.intensity(), "no chaos strength out of season")
        } finally {
            af.debugForceActive = previous
        }
    }

    @Test
    fun `an out-of-season launcher is left alone`() {
        val af = AprilFoolsLoader.instance
        val previous = af.debugForceActive
        try {
            af.debugForceActive = false
            assertFalse(af.isActive())
            assertEquals(0f, af.intensity())
        } finally {
            af.debugForceActive = previous
        }
    }
}
