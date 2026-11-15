package hivens.i18n

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** The message syntax: arguments, plural and select forms, nesting, and the few characters that need quoting. */
class MessageTest {

    private val english = PluralRules.of("one-other")
    private val russian = PluralRules.of("east-slavic")
    private val japanese = PluralRules.of("other")

    private fun format(text: String, vararg args: Any?, rule: PluralRule = english) = Message.parse(text).format(args, rule)

    @Test
    fun `numbered arguments are filled in order, and one not given stays as written`() {
        assertEquals("1 and two", format("{0} and {1}", 1, "two"))
        assertEquals("two then 1", format("{1} then {0}", 1, "two"))
        assertEquals("only {1}", format("only {1}", "x"))
        assertEquals("spaced 3", format("spaced { 0 }", 3))
    }

    @Test
    fun `a plural picks the form of the language's rule, and # is the number`() {
        val text = "{0, plural, one {# file} few {# files few} many {# files many} other {# files}}"
        assertEquals("1 file", format(text, 1))
        assertEquals("2 files", format(text, 2))
        assertEquals("21 file", format(text, 21, rule = russian))
        assertEquals("22 files few", format(text, 22, rule = russian))
        assertEquals("11 files many", format(text, 11, rule = russian))
        assertEquals("1 files", format(text, 1, rule = japanese))
    }

    @Test
    fun `an exact value comes before the category`() {
        val text = "{0, plural, =0 {nothing} one {one thing} other {# things}}"
        assertEquals("nothing", format(text, 0))
        assertEquals("one thing", format(text, 1))
        assertEquals("5 things", format(text, 5L))
    }

    @Test
    fun `a select picks by the argument's text, and other where nothing matches`() {
        val text = "{0, select, smartycraft {SmartyCraft account} other {sign in with '{0}'}}"
        assertEquals("SmartyCraft account", format(text, "smartycraft"))
        assertEquals("sign in with 'msa'", format(text, "msa"))
    }

    @Test
    fun `forms nest, and # inside a select inside a plural is still the plural's number`() {
        val text = "{0, plural, one {{1, select, mod {# mod} other {# thing}}} other {{1, select, mod {# mods} other {# things}}}}"
        assertEquals("1 mod", format(text, 1, "mod"))
        assertEquals("3 things", format(text, 3, "x"))
    }

    @Test
    fun `quoted braces and hash are literal, and any other apostrophe is itself`() {
        assertEquals("{literal} #1", format("'{'literal'}' #1"))
        assertEquals("2 # here", format("{0, plural, other {# '#' here}}", 2))
        assertEquals("it's 'quoted' {0", format("it's 'quoted' '{'0"))
    }

    @Test
    fun `a number given as text is still counted`() {
        assertEquals("1 file", format("{0, plural, one {# file} other {# files}}", "1"))
        assertEquals("x files", format("{0, plural, one {# file} other {# files}}", "x"))
    }

    @Test
    fun `what does not follow the syntax is refused with where`() {
        listOf(
            "{0, plural, one {x}}",
            "{0, select, a {x}}",
            "{0, plural, lots {x} other {y}}",
            "{0, plural, one {x} other {y}",
            "{name}",
            "{0, number}",
            "stray }",
            "{0, plural, one {x} one {y} other {z}}",
        ).forEach { text ->
            assertNotNull(Message.faultIn(text), text)
            assertFailsWith<MessageSyntaxException>(text) { Message.parse(text) }
        }
        assertNull(Message.faultIn("plain"))
    }

    @Test
    fun `every form a message can take is listed with its arguments empty`() {
        assertEquals(
            listOf("no files", " file", " files", "a  b"),
            Message.parse("{0, plural, =0 {no files} one {# file} other {# files}}").variants() + Message.parse("a {0} b").variants(),
        )
    }

    @Test
    fun `forms nest as deep as the limit and no deeper`() {
        fun nested(depth: Int) = "{0, select, other {".repeat(depth) + "x" + "}}".repeat(depth)
        assertEquals("x", format(nested(Message.MAX_DEPTH), "a"))
        assertNotNull(Message.faultIn(nested(Message.MAX_DEPTH + 1)))
        assertNotNull(Message.faultIn(nested(5000)), "a depth that would overflow the stack is refused as a message")
    }

    @Test
    fun `the arguments a message reads are known at any depth`() {
        assertEquals(setOf(0, 1, 2), Message.parse("{0} {1, plural, other {{2}}}").arguments)
    }
}
