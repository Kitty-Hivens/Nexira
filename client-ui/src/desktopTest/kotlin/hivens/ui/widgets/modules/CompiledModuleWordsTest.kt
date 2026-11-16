package hivens.ui.widgets.modules

import hivens.widget.api.ModuleStrings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every module compiled into the launcher ships its words as files in every language the launcher
 * ships, the same keys in each, its own name among them, read the way a jar's files are read.
 */
class CompiledModuleWordsTest {

    @Test
    fun `every compiled module ships the same words in every shipped language, its name among them`() {
        CompiledModules.forEach { module ->
            val tables = module.strings.tables
            assertEquals(ModuleStrings.SHIPPED_LANGUAGES.toSet(), tables.keys, "${module.id} ships a table per language")
            val keys = tables.getValue("en").keys
            tables.forEach { (tag, words) ->
                assertEquals(keys, words.keys, "${module.id} in $tag has the keys the English table has")
                assertTrue(words.values.none { it.isBlank() }, "${module.id} in $tag has an empty word")
            }
            assertTrue(module.name in keys, "${module.id} names itself by a key it ships")
        }
    }
}
