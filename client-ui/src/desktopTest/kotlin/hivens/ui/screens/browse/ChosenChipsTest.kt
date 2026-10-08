package hivens.ui.screens.browse

import hivens.core.api.dto.modrinth.ModrinthGameVersion
import hivens.launcher.modrinth.FilterField
import hivens.launcher.modrinth.SearchFilter
import hivens.ui.i18n.EnglishStrings
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

/** The chips under the search, with chosen game versions folded. */
class ChosenChipsTest {

    private val json = Json { ignoreUnknownKeys = true }

    private val versions: List<ModrinthGameVersion> by lazy {
        val text = checkNotNull(javaClass.classLoader.getResourceAsStream("catalogue/game_versions.json")).bufferedReader().use { it.readText() }
        json.decodeFromString<List<ModrinthGameVersion>>(text)
    }

    private fun v(name: String) = SearchFilter(FilterField.GameVersion, name)

    @Test
    fun `a whole minor line is one chip that takes every version off`() {
        val line = listOf("1.20", "1.20.1", "1.20.2", "1.20.3", "1.20.4", "1.20.5", "1.20.6").map(::v)
        val chips = chosenChips(line, versions, EnglishStrings)
        assertEquals(listOf("1.20.x"), chips.map { it.label })
        assertEquals(line.toSet(), chips.single().filters.toSet())
    }

    @Test
    fun `a run inside a line reads as a range, and a lone version as itself`() {
        val chips = chosenChips(listOf(v("1.20.2"), v("1.20.3"), v("1.20.4"), v("1.19.2")), versions, EnglishStrings)
        assertEquals(listOf("1.20.2-1.20.4", "1.19.2"), chips.map { it.label })
    }

    @Test
    fun `other filters keep a chip each, after the versions`() {
        val chips = chosenChips(
            listOf(SearchFilter(FilterField.Category, "magic"), v("1.20.1"), SearchFilter(FilterField.Loader, "fabric", excluded = true)),
            versions,
            EnglishStrings,
        )
        assertEquals(listOf("1.20.1", "Fabric", "Magic"), chips.map { it.label })
        assertEquals(listOf(false, true, false), chips.map { it.excluded })
    }

    @Test
    fun `without the catalogue's list every version is its own chip`() {
        val chips = chosenChips(listOf(v("1.20"), v("1.20.1")), emptyList(), EnglishStrings)
        assertEquals(listOf("1.20", "1.20.1"), chips.map { it.label })
    }
}
