package hivens.core.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuntimePrefsTest {

    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Test
    fun `both records answer the same launch questions`() {
        val prefs: List<RuntimePrefs> = listOf(InstanceProfile(), InstanceRuntime())
        for (p in prefs) {
            assertEquals(RuntimePrefs.WINDOW_WIDTH, p.windowWidth)
            assertEquals(RuntimePrefs.WINDOW_HEIGHT, p.windowHeight)
            assertEquals(false, p.fixedMemory)
        }
    }

    /**
     * The interface exists instead of a composed value type because both records
     * are on disk. Nesting the shared fields would push them a level down and an
     * instance would come back having forgotten its heap, so the encoded shape
     * has to stay flat.
     */
    @Test
    fun `the shared fields stay at the top level of the encoded record`() {
        val profile = json.encodeToString(InstanceProfile.serializer(), InstanceProfile(memoryMb = 8192)).let {
            json.parseToJsonElement(it).jsonObject
        }
        assertEquals("8192", profile.getValue("memoryMb").jsonPrimitive.content)
        assertTrue("windowWidth" in profile.keys)
        assertTrue("fullScreen" in profile.keys)

        val runtime = json.encodeToString(InstanceRuntime.serializer(), InstanceRuntime(memoryMb = 8192)).let {
            json.parseToJsonElement(it).jsonObject
        }
        assertEquals("8192", runtime.getValue("memoryMb").jsonPrimitive.content)
        assertTrue("windowWidth" in runtime.keys)
        assertTrue("fullScreen" in runtime.keys)
    }

    /**
     * A fresh record pins no heap, whichever of the two it is. The launch path
     * sizes an unpinned instance from the machine and the adaptive sizer, so a
     * number here would be one nothing reads and the two used to disagree on.
     */
    @Test
    fun `neither record is born pinning a heap`() {
        assertEquals(RuntimePrefs.NO_PINNED_MEMORY, InstanceProfile().memoryMb)
        assertEquals(RuntimePrefs.NO_PINNED_MEMORY, InstanceRuntime().memoryMb)
        assertFalse(InstanceProfile().fixedMemory)
        assertFalse(InstanceRuntime().fixedMemory)
    }
}
