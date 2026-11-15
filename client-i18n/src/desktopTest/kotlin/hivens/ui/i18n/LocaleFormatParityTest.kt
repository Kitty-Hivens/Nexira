package hivens.ui.i18n

import hivens.i18n.Message
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A message must never carry a printf conversion specifier. Arguments go into a
 * message as `{0}`, `{1}`, which every language's table writes out itself, and
 * a %s or %d left in a table is either text a person was never meant to read or
 * a string some caller still runs through String.format(), where a dropped,
 * reordered or mistyped one crashes at runtime (MissingFormatArgumentException).
 * Every form of every message in every shipped table is read.
 */
class LocaleFormatParityTest {

    // '%', optional argument index, flags, optional width/precision, then a
    // value-consuming conversion letter. Space and ',' are deliberately excluded
    // from the flag class so a literal "~1% overhead" or "~5%, captures" -- where
    // a non-flag char sits between '%' and any letter -- is not a false positive.
    // '%%'/'%n' consume no argument and cannot crash, so they are not matched.
    private val printfConversion =
        Regex("""%(?:\d+\${'$'})?[-#+0(]*\d*(?:\.\d+)?[bBhHsScCdoxXeEfgGaA]""")

    @Test
    fun `messages carry no printf conversion specifier`() {
        val violations = buildList {
            for ((tag, table) in NexiraStrings.tables()) {
                for ((key, text) in table) {
                    val forms = runCatching { Message.parse(text).variants() }.getOrDefault(listOf(text))
                    val match = forms.firstNotNullOfOrNull { printfConversion.find(it) } ?: continue
                    add("$tag.$key: \"$text\" contains \"${match.value}\"")
                }
            }
        }

        assertTrue(
            violations.isEmpty(),
            "Messages take their arguments as {0}, {1}, not printf placeholders:\n" +
                violations.joinToString("\n") { "  $it" },
        )
    }

    @Test
    fun `detector catches conversions and ignores literal percents`() {
        // Would have caught the original "%s  (%d / %d)" install-progress string.
        for (s in listOf("%s  (%d / %d)", "%d files", "%.1f MB", "%1\$s done", "%x")) {
            assertTrue(printfConversion.containsMatchIn(s), "should flag: $s")
        }
        // Literal percents that live in real locale strings -- must not flag.
        for (s in listOf("~1% overhead", "~5%, captures method-level", "as % of heap", "50%")) {
            assertTrue(!printfConversion.containsMatchIn(s), "should not flag: $s")
        }
    }
}
