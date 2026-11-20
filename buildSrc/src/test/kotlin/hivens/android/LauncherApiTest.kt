package hivens.android

import org.gradle.api.GradleException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class LauncherApiTest {

    @Test
    fun `a level with a minor part names both`() {
        assertEquals(37 to 2, parseApiLevel("37.2"))
    }

    @Test
    fun `a bare level has no minor part, as the property was written before`() {
        assertEquals(37 to null, parseApiLevel("37"))
        assertEquals(36 to null, parseApiLevel(" 36 "))
    }

    @Test
    fun `anything else is refused rather than read as some level`() {
        listOf("", "37.", "37.x", "37.1.0", "thirty-seven").forEach { text ->
            assertFailsWith<GradleException>(text) { parseApiLevel(text) }
        }
    }
}
