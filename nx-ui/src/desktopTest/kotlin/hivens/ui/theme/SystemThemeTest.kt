package hivens.ui.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Contract of the OS colour-scheme parsers -- pure text in, tri-state out. */
class SystemThemeTest {

    // -- XDG portal (gdbus + busctl shapes) --

    @Test
    fun portal_gdbus_double_variant_dark() {
        assertEquals(true, parsePortalColorScheme("(<<uint32 1>>,)"))
    }

    @Test
    fun portal_gdbus_single_variant_light() {
        assertEquals(false, parsePortalColorScheme("(<uint32 2>,)"))
    }

    @Test
    fun portal_busctl_dark() {
        assertEquals(true, parsePortalColorScheme("v v u 1"))
    }

    @Test
    fun portal_busctl_single_variant_light() {
        assertEquals(false, parsePortalColorScheme("v u 2"))
    }

    @Test
    fun portal_no_preference_is_unknown() {
        assertNull(parsePortalColorScheme("(<<uint32 0>>,)"))
        assertNull(parsePortalColorScheme("v v u 0"))
    }

    @Test
    fun portal_garbage_is_unknown() {
        assertNull(parsePortalColorScheme(""))
        assertNull(parsePortalColorScheme("Error: not found"))
        assertNull(parsePortalColorScheme("(<<uint32 7>>,)"))
    }

    // -- Windows registry --

    @Test
    fun registry_zero_is_dark() {
        val out = """
            HKEY_CURRENT_USER\SOFTWARE\Microsoft\Windows\CurrentVersion\Themes\Personalize
                AppsUseLightTheme    REG_DWORD    0x0
        """.trimIndent()
        assertEquals(true, parseAppsUseLightTheme(out))
    }

    @Test
    fun registry_one_is_light() {
        val out = """
            HKEY_CURRENT_USER\SOFTWARE\Microsoft\Windows\CurrentVersion\Themes\Personalize
                AppsUseLightTheme    REG_DWORD    0x1
        """.trimIndent()
        assertEquals(false, parseAppsUseLightTheme(out))
    }

    @Test
    fun registry_missing_value_is_unknown() {
        assertNull(parseAppsUseLightTheme("ERROR: The system was unable to find the specified registry key or value."))
        assertNull(parseAppsUseLightTheme(""))
    }

    // -- macOS defaults --

    @Test
    fun defaults_dark_key_present() {
        assertTrue(parseAppleInterfaceStyle(exit = 0, stdout = "Dark\n"))
    }

    @Test
    fun defaults_missing_key_is_light() {
        assertFalse(parseAppleInterfaceStyle(exit = 1, stdout = ""))
    }
}
