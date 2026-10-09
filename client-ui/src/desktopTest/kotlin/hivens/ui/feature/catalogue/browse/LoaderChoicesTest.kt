package hivens.ui.feature.catalogue.browse

import hivens.core.api.dto.modrinth.ModrinthCategoryTag
import hivens.core.api.dto.modrinth.ModrinthLoaderTag
import hivens.ui.i18n.EnglishStrings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Which loaders the rail offers each type. */
class LoaderChoicesTest {

    private val tags = BrowseTags.Tags(
        gameVersions = emptyList(),
        categories = listOf(ModrinthCategoryTag("16x", projectType = "resourcepack", header = "resolutions")),
        loaders = listOf(
            ModrinthLoaderTag("minecraft", listOf("resourcepack")),
            ModrinthLoaderTag("fabric", listOf("mod", "modpack")),
            ModrinthLoaderTag("iris", listOf("shader")),
            ModrinthLoaderTag("paper", listOf("plugin", "mod")),
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
