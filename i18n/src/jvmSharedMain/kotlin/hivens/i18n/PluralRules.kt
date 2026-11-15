package hivens.i18n

import kotlin.math.absoluteValue

/** Which plural form a whole number takes in a language: one of [Message.CATEGORIES]. */
fun interface PluralRule {
    fun categoryOf(n: Long): String
}

/**
 * The plural rule families a language can name, by the name a language declares (the
 * `plural` key of its `language.properties`). They follow the CLDR rules for whole numbers.
 *
 * | Family        | Forms                  | Languages                         |
 * |---------------|------------------------|-----------------------------------|
 * | `other`       | other                  | ja, zh, ko, vi, th                |
 * | `one-other`   | one, other             | en, de, nl, sv, it, es, pt-PT     |
 * | `french`      | one (0 and 1), other   | fr, pt-BR                         |
 * | `east-slavic` | one, few, many, other  | ru, uk, be                        |
 * | `west-slavic` | one, few, other        | cs, sk                            |
 * | `polish`      | one, few, many, other  | pl                                |
 *
 * A whole number reaches `other` in the slavic families only through [Message]'s fallback,
 * since CLDR keeps `other` for fractions there. A message still has to give it.
 */
object PluralRules {

    private val families: Map<String, PluralRule> = mapOf(
        "other" to PluralRule { "other" },
        "one-other" to PluralRule { n -> if (n == 1L) "one" else "other" },
        "french" to PluralRule { n -> if (n == 0L || n == 1L) "one" else "other" },
        "east-slavic" to PluralRule { n ->
            val mod10 = n.absoluteValue % 10
            val mod100 = n.absoluteValue % 100
            when {
                mod10 == 1L && mod100 != 11L -> "one"
                mod10 in 2L..4L && mod100 !in 12L..14L -> "few"
                else -> "many"
            }
        },
        "west-slavic" to PluralRule { n ->
            when (n.absoluteValue) {
                1L -> "one"
                in 2L..4L -> "few"
                else -> "other"
            }
        },
        "polish" to PluralRule { n ->
            val mod10 = n.absoluteValue % 10
            val mod100 = n.absoluteValue % 100
            when {
                n.absoluteValue == 1L -> "one"
                mod10 in 2L..4L && mod100 !in 12L..14L -> "few"
                else -> "many"
            }
        },
    )

    /** The family names a language may declare. */
    val names: Set<String> get() = families.keys

    /** The rule of [family], or null for a name that is none of [names]. */
    fun find(family: String): PluralRule? = families[family]

    /** The rule of [family], and the one that only knows `other` for a name that is none. */
    fun of(family: String): PluralRule = families[family] ?: families.getValue("other")
}
