package hivens.i18n

import hivens.i18n.api.LanguageInfo
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Where a key is looked up and in what order (the language, the languages it falls back
 * to, English, the key), and the layers words come from: an owner's own, other owners'
 * translations, the person's files.
 */
class I18nEngineTest {

    private val dir: Path = Files.createTempDirectory("i18n-engine")

    @AfterTest
    fun cleanUp() {
        dir.toFile().deleteRecursively()
    }

    private fun file(tag: String, name: String, text: String) {
        dir.resolve(tag).createDirectories().resolve(name).writeText(text, Charsets.UTF_8)
    }

    private val launcher = mapOf(
        "nexira" to mapOf(
            "en" to mapOf("hello" to "Hello", "only.en" to "English only", "files" to "{0, plural, one {# file} other {# files}}"),
            "ru" to mapOf("hello" to "Привет", "only.ru" to "Только по-русски", "files" to "{0, plural, one {# файл} few {# файла} many {# файлов} other {# файла}}"),
        ),
    )

    private fun engine(language: String, userDir: Path? = dir) = I18nEngine(userDir, language).apply { contribute("nexira", launcher) }

    private fun text(engine: I18nEngine, key: String, vararg args: Any?) = engine.strings("nexira").text(key, *args)

    @Test
    fun `the person's language comes first, then English, then the key itself`() {
        val ru = engine("ru")
        assertEquals("Привет", text(ru, "hello"))
        assertEquals("English only", text(ru, "only.en"))
        assertEquals("nobody.has.this", text(ru, "nobody.has.this"))
        assertEquals("21 файл", text(ru, "files", 21))
        assertEquals("21 files", text(engine("en"), "files", 21))
    }

    @Test
    fun `a language with no words of its own is read in English`() {
        val ja = engine("ja")
        assertEquals("Hello", text(ja, "hello"))
        assertEquals("ja", ja.strings("nexira").language)
    }

    @Test
    fun `a language nobody installed reads as English and keeps the choice`() {
        val engine = engine("xx")
        assertEquals("Hello", text(engine, "hello"))
        assertEquals("en", engine.current.value.language.tag)
        assertEquals("xx", engine.language)
    }

    @Test
    fun `a custom language falls back through the chain it declares, then English, then the key`() {
        file("uk", "language.properties", "name=Українська\nplural=east-slavic\nfallback=ru\n")
        file("uk", "nexira.properties", "hello=Привіт\n")
        val uk = engine("uk")
        assertEquals(LanguageInfo("uk", "Українська", "east-slavic", listOf("ru")), uk.current.value.language)
        assertEquals("Привіт", text(uk, "hello"))
        assertEquals("Только по-русски", text(uk, "only.ru"), "a key only Russian has")
        assertEquals("English only", text(uk, "only.en"), "a key only English has")
        assertEquals("nobody.has.this", text(uk, "nobody.has.this"), "a key nobody has")
        assertEquals("22 файла", text(uk, "files", 22), "a Russian message is read by the Russian rule")
        assertTrue(uk.problems.isEmpty(), uk.problems.toString())
    }

    @Test
    fun `a chain is followed through every language it names, depth first`() {
        file("be", "language.properties", "name=Беларуская\nplural=east-slavic\nfallback=uk\n")
        file("uk", "language.properties", "name=Українська\nplural=east-slavic\nfallback=ru\n")
        file("uk", "nexira.properties", "hello=Привіт\n")
        val be = engine("be")
        assertEquals("Привіт", text(be, "hello"))
        assertEquals("Только по-русски", text(be, "only.ru"))
    }

    @Test
    fun `a cycle is cut with a warning and both languages still load`() {
        file("aa", "language.properties", "name=A\nfallback=bb\n")
        file("bb", "language.properties", "name=B\nfallback=aa,ru\n")
        file("bb", "nexira.properties", "only.b=from B\n")
        val aa = engine("aa")
        assertEquals("from B", text(aa, "only.b"))
        assertEquals("Только по-русски", text(aa, "only.ru"), "the rest of the chain is still followed")
        assertEquals("English only", text(aa, "only.en"))
        assertTrue(aa.problems.any { "cycle" in it }, aa.problems.toString())
        assertEquals(listOf("aa", "bb"), aa.languages.value.map { it.tag }.filter { it in setOf("aa", "bb") })
    }

    @Test
    fun `a language that falls back to itself or to one not installed has that step skipped with a warning`() {
        file("uk", "language.properties", "name=Українська\nplural=east-slavic\nfallback=uk,pl,ru\n")
        val uk = engine("uk")
        assertEquals("Только по-русски", text(uk, "only.ru"))
        assertTrue(uk.problems.any { "itself" in it }, uk.problems.toString())
        assertTrue(uk.problems.any { "'pl'" in it && "not installed" in it }, uk.problems.toString())
    }

    @Test
    fun `English ends every chain even when a language names it nowhere`() {
        file("aa", "language.properties", "name=A\nfallback=ru\n")
        assertEquals("English only", text(engine("aa"), "only.en"))
    }

    @Test
    fun `the person's files override shipped words key by key, in a shipped language too`() {
        file("ru", "nexira.properties", "hello=Здорово\n")
        val ru = engine("ru")
        assertEquals("Здорово", text(ru, "hello"))
        assertEquals("Только по-русски", text(ru, "only.ru"), "the keys the file leaves out stay shipped")
    }

    @Test
    fun `a folder with no language file still adds the language, with a warning`() {
        file("eo", "nexira.properties", "hello=Saluton\n")
        val eo = engine("eo")
        assertEquals("Saluton", text(eo, "hello"))
        assertEquals(LanguageInfo("eo", "eo", "other"), eo.current.value.language)
        assertTrue(eo.problems.any { "eo" in it && "language.properties" in it }, eo.problems.toString())
    }

    @Test
    fun `an unknown plural family uses the other forms only, with a warning`() {
        file("tlh", "language.properties", "name=tlhIngan\nplural=klingon\n")
        file("tlh", "nexira.properties", "files={0, plural, one {# one} other {# many}}\n")
        val tlh = engine("tlh")
        assertEquals("1 many", text(tlh, "files", 1))
        assertTrue(tlh.problems.any { "klingon" in it }, tlh.problems.toString())
    }

    @Test
    fun `a module's own words and its words for another namespace both register`() {
        val engine = engine("ru")
        engine.contribute(
            "example.module",
            mapOf(
                "example.module" to mapOf("en" to mapOf("name" to "Example"), "ru" to mapOf("name" to "Пример")),
                "nexira" to mapOf("ru" to mapOf("hello" to "Здравствуйте"), "pl" to mapOf("hello" to "Cześć")),
            ),
        )
        assertEquals("Пример", engine.strings("example.module").text("name"))
        assertEquals("Здравствуйте", text(engine, "hello"), "a translation another owner carries is laid over the shipped one")
        engine.withdraw("example.module")
        assertEquals("Привет", text(engine, "hello"))
        assertEquals("name", engine.strings("example.module").text("name"))
    }

    @Test
    fun `the person's files are read again when the language changes`() {
        val engine = engine("en")
        file("ru", "nexira.properties", "hello=Здорово\n")
        engine.setLanguage("ru")
        assertEquals("Здорово", text(engine, "hello"))
        assertEquals("ru", engine.current.value.language.tag)
    }

    @Test
    fun `a subtree in another language reads its words, and an unknown tag keeps the current one`() {
        val engine = engine("en")
        assertEquals("Привет", engine.current.value.inLanguage("ru").strings("nexira").text("hello"))
        assertEquals("Hello", engine.current.value.inLanguage("zz").strings("nexira").text("hello"))
    }

    @Test
    fun `the shipped languages come first, in their order, and a custom one after`() {
        file("uk", "language.properties", "name=Українська\nplural=east-slavic\nfallback=ru\n")
        assertEquals(listOf("ru", "en", "de", "ja", "uk"), engine("en").languages.value.map { it.tag })
    }

    @Test
    fun `a malformed message is shown as written rather than as its key`() {
        val engine = I18nEngine(null, "en").apply { contribute("nexira", mapOf("nexira" to mapOf("en" to mapOf("bad" to "{0, plural, one {x}}")))) }
        assertEquals("{0, plural, one {x}}", engine.strings("nexira").text("bad", 1))
        assertFalse(engine.strings("nexira").has("missing"))
    }

    @Test
    fun `fallbacks are followed depth first, a first fallback's own fallbacks before the second`() {
        file("aa", "language.properties", "name=A\nfallback=bb,cc\n")
        file("bb", "language.properties", "name=B\nfallback=dd\n")
        file("cc", "language.properties", "name=C\n")
        file("dd", "language.properties", "name=D\n")
        file("cc", "nexira.properties", "pick=from C\n")
        file("dd", "nexira.properties", "pick=from D\n")
        val aa = engine("aa")
        assertEquals("from D", text(aa, "pick"))
        assertEquals(listOf("aa", "bb", "dd", "cc"), aa.readingOrder)
    }

    @Test
    fun `the person's files override what a module carries for the launcher's words`() {
        file("ru", "nexira.properties", "hello=Из файла\n")
        val ru = engine("ru")
        ru.contribute("pack", mapOf("nexira" to mapOf("ru" to mapOf("hello" to "Из модуля"))))
        assertEquals("Из файла", text(ru, "hello"))
    }

    @Test
    fun `the reading order is the language and what it falls back to, English only where named`() {
        file("uk", "language.properties", "name=Українська\nplural=east-slavic\nfallback=ru\n")
        assertEquals(listOf("uk", "ru"), engine("uk").readingOrder)
        assertEquals(listOf("de"), engine("de").readingOrder)
        assertEquals(listOf("en"), engine("xx").readingOrder, "a language not installed reads in English")
    }

    @Test
    fun `a message nested past the limit in a person's file is refused, not a crash`() {
        val deep = "{0, select, other {".repeat(5000) + "x" + "}}".repeat(5000)
        file("ru", "nexira.properties", "deep=$deep\nhello=Ещё работает\n")
        val ru = engine("ru")
        assertEquals(deep, text(ru, "deep", "a"), "shown as written")
        assertEquals("Ещё работает", text(ru, "hello"))
    }

    @Test
    fun `a file that is not all UTF-8 is read with a warning naming it`() {
        val folder = dir.resolve("ru").createDirectories()
        Files.write(folder.resolve("nexira.properties"), "hello=\u041f\u0440\u0438\n".toByteArray(Charsets.UTF_8) + byteArrayOf(0xFF.toByte()) + "\nbye=ok\n".toByteArray())
        val ru = engine("ru")
        assertEquals("ok", text(ru, "bye"), "the rest of the file still counts")
        assertTrue(ru.problems.any { "nexira.properties" in it && "UTF-8" in it }, ru.problems.toString())
    }

    @Test
    fun `an owner can add a language, the person's declaration of it wins field by field, and it leaves with its owner`() {
        val engine = engine("uk")
        engine.contribute("pack", mapOf("nexira" to mapOf("uk" to mapOf("hello" to "Привіт"))), mapOf("uk" to mapOf("name" to "Українська", "fallback" to "ru")))
        assertEquals(LanguageInfo("uk", "Українська", "other", listOf("ru")), engine.current.value.language)
        assertEquals("Только по-русски", text(engine, "only.ru"))
        file("uk", "language.properties", "plural=east-slavic\n")
        engine.reload()
        assertEquals(LanguageInfo("uk", "Українська", "east-slavic", listOf("ru")), engine.current.value.language)
        dir.resolve("uk").toFile().deleteRecursively()
        engine.reload()
        engine.withdraw("pack")
        assertEquals("en", engine.current.value.language.tag)
    }
}
