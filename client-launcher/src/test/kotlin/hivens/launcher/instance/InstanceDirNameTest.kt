package hivens.launcher.instance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class InstanceDirNameTest {

    private val id = "0f8c2a51-7d3e-4b6a-9c1d-2e4f6a8b0c1d"

    @Test
    fun `a long name never pushes the id out`() {
        val name = instanceDirName("A".repeat(200), id)

        assertTrue(name.endsWith("-$id"), name)
        assertTrue(name.length <= 96, "still bounded: ${name.length}")
    }

    /** Every Cyrillic letter becomes an underscore, so two names of one length used to reduce to one directory. */
    @Test
    fun `two names that sanitise alike still get their own directories`() {
        val other = "7a1b3c5d-9e2f-4a6b-8c0d-1e3f5a7b9c2d"

        assertNotEquals(instanceDirName("Индустриальная", id), instanceDirName("Технологичнаяя", other))
        assertEquals("______________-$id", instanceDirName("Индустриальная", id))
    }
}
