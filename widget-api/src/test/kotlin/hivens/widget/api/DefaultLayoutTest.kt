package hivens.widget.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DefaultLayoutTest {

    @Test
    fun `bundled default decodes to the four kernel surfaces`() {
        val graph = DefaultLayout.load()
        val surfaceIds = graph.surfaces.keys.map { it.value }.toSet()
        assertEquals(
            setOf("home.classic", "home.libraryfirst", "appshell.leftrail", "appshell.rightrail"),
            surfaceIds,
            "kernel-2 default ships four named surfaces; any drift means kernel-3 forgot to keep them",
        )
    }

    @Test
    fun `each kernel surface has a main slot with no widgets`() {
        val graph = DefaultLayout.load()
        graph.surfaces.forEach { (surfaceId, layout) ->
            val main = layout.slots[SlotId("main")]
            assertNotNull(main, "surface ${surfaceId.value} must declare a `main` slot")
            assertTrue(
                main.widgets.isEmpty(),
                "kernel-2 ships empty slots; widget kinds come in kernel-3",
            )
        }
    }
}
