package hivens.ui.i18n

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A translator-static string must never carry a printf conversion specifier.
 * Such a string is rendered with String.format(), where a dropped, reordered or
 * mistyped %s/%d crashes at runtime (MissingFormatArgumentException) with no
 * compile-time or CI guard. Strings that interpolate a value go through fun-style
 * overrides ($-interpolation), which the compiler checks. This catches a
 * regression that reintroduces the %s/%d pattern on a plain val.
 */
class LocaleFormatParityTest {

    private val locales = mapOf(
        "EnglishStrings" to EnglishStrings,
        "RussianStrings" to RussianStrings,
        "GermanStrings"  to GermanStrings,
    )

    // '%', optional argument index, flags, optional width/precision, then a
    // value-consuming conversion letter. Space and ',' are deliberately excluded
    // from the flag class so a literal "~1% overhead" or "~5%, captures" -- where
    // a non-flag char sits between '%' and any letter -- is not a false positive.
    // '%%'/'%n' consume no argument and cannot crash, so they are not matched.
    private val printfConversion =
        Regex("""%(?:\d+\${'$'})?[-#+0(]*\d*(?:\.\d+)?[bBhHsScCdoxXeEfgGaA]""")

    @Test
    fun `static strings carry no printf conversion specifier`() {
        val staticGetters = AppStrings::class.java.declaredMethods
            .filter { it.parameterCount == 0 && it.returnType == String::class.java }

        val violations = buildList {
            for ((name, locale) in locales) {
                for (getter in staticGetters) {
                    val value = getter.invoke(locale) as String
                    val match = printfConversion.find(value) ?: continue
                    add("$name.${getter.name}: \"$value\" contains \"${match.value}\"")
                }
            }
        }

        assertTrue(
            violations.isEmpty(),
            "Translator-static strings must use fun-style \$-interpolation, not printf placeholders:\n" +
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
