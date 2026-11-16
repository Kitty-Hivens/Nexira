package hivens.widget.api

import hivens.widget.model.ScopeLevel
import hivens.widget.model.SourceKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BootValuesTest {

    private val volume = SourceKey<Int>("test.volume", ScopeLevel.App)
    private val label = SourceKey<String?>("test.label", ScopeLevel.App)

    @Test
    fun `a value put for a key is read back for it, and a key given none reads null`() {
        val values = BootValues.of { put(volume, 7) }

        assertEquals(7, values[volume])
        assertNull(values[label])
        assertEquals(setOf(volume.id), values.ids)
    }

    @Test
    fun `a key given null is told apart from a key given nothing`() {
        val values = BootValues.of { put(label, null) }

        assertTrue(label in values)
        assertNull(values[label])
        assertFalse(volume in values)
    }

    @Test
    fun `values laid over others keep their own and take the rest`() {
        val first = BootValues.of { put(volume, 1) }
        val second = BootValues.of {
            put(volume, 2)
            put(label, "second")
        }

        val both = first over second

        assertEquals(1, both[volume])
        assertEquals("second", both[label])
        assertEquals(both.ids, (second over first).ids)
        assertEquals(2, (second over first)[volume])
        assertEquals(BootValues.NONE.ids, emptySet())
    }

    @Test
    fun `only an app source is answered before the host is up`() {
        assertFailsWith<IllegalArgumentException> { BootValues.of { put(SourceKey<Int>("test.window", ScopeLevel.Window), 1) } }
    }
}
