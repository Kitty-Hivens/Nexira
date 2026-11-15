package hivens.ui.i18n

import hivens.i18n.Message
import hivens.i18n.ShippedLanguages
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The launcher's own tables as shipped: every message parses, and every language reads the
 * arguments English gives it and no others.
 *
 * A missing translation is not a failure here, a key a language lacks or a key the code looks
 * up that English does not have alike: it shows the next language of the chain, or the key, at
 * runtime, and the build only warns about it (`checkTranslations`). A message that does not
 * parse is a bug in a shipped table, and fails.
 */
class NexiraTablesTest {

    private val tables = NexiraStrings.tables()
    private val english = tables.getValue("en")

    @Test
    fun `every shipped language has a table`() {
        assertEquals(ShippedLanguages.tags, tables.keys)
    }

    @Test
    fun `every message in every table parses`() {
        val malformed = tables.flatMap { (tag, table) ->
            table.mapNotNull { (key, text) -> Message.faultIn(text)?.let { "$tag.$key: $it" } }
        }
        assertEquals(emptyList(), malformed)
    }

    @Test
    fun `a translation reads only the arguments English gives it`() {
        val wrong = tables.filterKeys { it != "en" }.flatMap { (tag, table) ->
            table.mapNotNull { (key, text) ->
                val base = english[key]?.let { Message.parse(it).arguments } ?: return@mapNotNull null
                val own = Message.parse(text).arguments
                (own - base).takeIf { it.isNotEmpty() }?.let { "$tag.$key reads {${it.joinToString()}}, English gives {${base.joinToString()}}" }
            }
        }
        assertEquals(emptyList(), wrong)
    }
}
