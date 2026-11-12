package hivens.widget.api

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * The number the module builds stamp into a jar is the number the loader accepts.
 * Two places held it before, and a jar stamped with the old one is refused by
 * every launcher built after the bump.
 */
class WidgetApiVersionTest {

    @Test
    fun `the stamped version is the version the loader speaks`() {
        val stamped = assertNotNull(System.getProperty("nexira.widgetApi"), "the build passes nexira.widgetApi to the tests")
        assertEquals(WidgetApi.VERSION.toString(), stamped, "bump nexira.widgetApi in gradle.properties with WidgetApi.VERSION")
    }
}
