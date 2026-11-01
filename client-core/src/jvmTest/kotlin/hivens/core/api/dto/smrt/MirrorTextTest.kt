package hivens.core.api.dto.smrt

import hivens.core.update.PackBuild
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class MirrorTextTest {

    private val byLanguage = mapOf("en" to "Heavy industry.", "ru" to "Тяжёлая промышленность.")

    @Test
    fun `the reader's language reads the translation`() {
        assertEquals("Heavy industry.", inLanguage("Тяжёлая промышленность.", byLanguage, "en"))
    }

    @Test
    fun `a language the curator did not write reads the untagged copy`() {
        assertEquals("Тяжёлая промышленность.", inLanguage("Тяжёлая промышленность.", byLanguage, "de"))
    }

    @Test
    fun `no map reads the untagged copy, as before there were maps`() {
        assertEquals("plain", inLanguage("plain", null, "en"))
        assertNull(inLanguage(null, null, "en"))
    }

    @Test
    fun `the tag is matched lower-cased, the way the mirror ships it`() {
        assertEquals("Heavy industry.", inLanguage("x", byLanguage, " EN "))
    }

    @Test
    fun `a summary from a mirror with maps decodes them, one without decodes as it did`() {
        val json = Json { ignoreUnknownKeys = true }
        val base = """"pack_id":"p","display_name":"P","tagline":"ru","minecraft_version":"1.21.1","latest_pack_version":"1""""
        val withMaps = json.decodeFromString<SmrtPackSummary>("""{$base,"tagline_i18n":{"en":"en"},"description_md_i18n":{"en":"# en"}}""")
        assertEquals(mapOf("en" to "en"), withMaps.taglineI18n)
        assertEquals(mapOf("en" to "# en"), withMaps.descriptionMdI18n)
        val without = json.decodeFromString<SmrtPackSummary>("{$base}")
        assertNull(without.taglineI18n)
        assertNull(without.descriptionMdI18n)
    }

    @Test
    fun `a build's notes follow the reader, and a build without a map is left alone`() {
        val build = PackBuild(versionNumber = "1", changelog = "ru notes", changelogI18n = mapOf("en" to "en notes"))
        assertEquals("en notes", build.forLanguage("en").changelog)
        assertEquals("ru notes", build.forLanguage("ja").changelog)
        val plain = PackBuild(versionNumber = "1", changelog = "notes")
        assertSame(plain, plain.forLanguage("en"))
    }
}
