package hivens.ui.feature.catalogue.browse

import hivens.catalogue.api.CatalogueTags
import hivens.catalogue.api.CategoryTag
import hivens.catalogue.api.LoaderTag
import hivens.ui.i18n.EnglishStrings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Which loaders the rail offers each type. */
class LoaderChoicesTest {

    private val tags = CatalogueTags(
        gameVersions = emptyList(),
        categories = listOf(CategoryTag("16x", projectType = "resourcepack", header = "resolutions")),
        loaders = listOf(
            LoaderTag("minecraft", listOf("resourcepack")),
            LoaderTag("fabric", listOf("mod", "modpack")),
            LoaderTag("iris", listOf("shader")),
            LoaderTag("paper", listOf("plugin", "mod")),
        ),
    )

    private fun loaders(type: String) =
        choicesFor(FilterGroup.Loader, type, tags, EnglishStrings).map { it.filter.value }

    @Test
    fun `a resource pack is offered no loader, which would undo a chosen resolution`() {
        assertTrue(loaders("resourcepack").isEmpty())
    }

    @Test
    fun `mods and shaders keep the loaders they are published for`() {
        assertEquals(listOf("fabric"), loaders("mod"), "a server platform is not what a mod search means")
        assertEquals(listOf("iris"), loaders("shader"))
    }
}
