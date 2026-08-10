package hivens.ui.utils

import hivens.ui.i18n.EnglishStrings
import hivens.ui.i18n.GermanStrings
import hivens.ui.i18n.RussianStrings
import java.util.Locale
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A size is rendered in the language the interface is in, not the one the machine
 * is configured for. Both tests below run under a default locale that disagrees
 * with the strings being asked, which is the case that produced "1.5 GB" inside a
 * Russian sentence.
 */
class HumanSizeTest {

    private lateinit var systemLocale: Locale

    @BeforeTest
    fun setUp() {
        systemLocale = Locale.getDefault()
    }

    @AfterTest
    fun tearDown() {
        Locale.setDefault(systemLocale)
    }

    @Test
    fun `russian strings render russian units and decimal mark on an english system`() {
        Locale.setDefault(Locale.ENGLISH)

        assertEquals("1,5 ГБ", humanSize(1_610_612_736L, RussianStrings))
        assertEquals("300 Б", humanSize(300L, RussianStrings))
    }

    @Test
    fun `english strings keep the dot on a russian system`() {
        Locale.setDefault(Locale.of("ru", "RU"))

        assertEquals("1.5 GB", humanSize(1_610_612_736L, EnglishStrings))
        assertEquals("300 B", humanSize(300L, EnglishStrings))
    }

    @Test
    fun `the unit climbs with the number`() {
        assertEquals("1.0 KB", humanSize(1024L, EnglishStrings))
        assertEquals("1.0 MB", humanSize(1024L * 1024, EnglishStrings))
        assertEquals("1.0 TB", humanSize(1024L * 1024 * 1024 * 1024, EnglishStrings))
        // Nothing above the last unit: petabytes read as four-digit terabytes.
        assertEquals("1024.0 TB", humanSize(1024L * 1024 * 1024 * 1024 * 1024, EnglishStrings))
    }

    @Test
    fun `german renders its own decimal mark`() {
        Locale.setDefault(Locale.ENGLISH)

        assertEquals("1,5 GB", humanSize(1_610_612_736L, GermanStrings))
    }
}
