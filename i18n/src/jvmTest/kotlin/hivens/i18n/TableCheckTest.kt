package hivens.i18n

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the build warns about a namespace's tables: a language that lacks keys, a lookup English cannot answer, a message that does not parse. */
class TableCheckTest {

    private val dir: File = Files.createTempDirectory("table-check").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun file(name: String, text: String) = File(dir, name).apply { parentFile.mkdirs(); writeText(text) }

    @Test
    fun `missing translations, unknown lookups and malformed messages are each one warning`() {
        val tables = File(dir, "tables")
        file("tables/en.properties", "hello=Hello\nfiles={0, plural, one {# file} other {# files}}\nbye=Bye\n")
        file("tables/de.properties", "hello=Hallo\nfiles={0, plural, one {# Datei}}\n")
        val source = file("src/Screen.kt", "val a = s.text(\"hello\")\nval b = s.text(\"nobody\", 3)\nval c = s.text(key)\n")

        val warnings = TableCheck.check(tables, "en", listOf(source))

        assertEquals(3, warnings.size, warnings.joinToString("\n"))
        assertTrue(warnings.any { it.startsWith("de: 'files' is not a valid message") }, warnings.toString())
        assertTrue(warnings.any { it.startsWith("de: 1 key(s)") && "bye" in it }, warnings.toString())
        assertTrue(warnings.any { "Screen.kt:2: 'nobody'" in it }, warnings.toString())
    }

    @Test
    fun `tables that agree with the code say nothing`() {
        val tables = File(dir, "tables")
        file("tables/en.properties", "hello=Hello\n")
        file("tables/ru.properties", "hello=Привет\n")
        assertEquals(emptyList(), TableCheck.check(tables, "en", listOf(file("src/A.kt", "s.text(\"hello\")"))))
    }
}
