package hivens.look.manager

import hivens.look.manager.api.LookApi
import hivens.look.manager.api.LookSource
import hivens.widget.api.ModuleContribution
import hivens.widget.model.ApiVersion
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Each axis is answered by the first provider that is there of the family the choice names for it: the
 * person's own for the axis, the chosen pack's, the default pack's, and the axis's base answers where
 * none is.
 */
class ResolutionTest {

    private enum class Override { None, Present, Missing }
    private enum class Pack { Unchosen, Names, Omits, NamesMissing, Unknown }
    private enum class Default { Names, Omits, Absent }

    @Test
    fun `every combination of override, pack and default pack resolves down the chain`() {
        for (override in Override.entries) for (pack in Pack.entries) for (default in Default.entries) {
            val items = mutableListOf<ModuleContribution>(
                TestAxis("shade"),
                TestProvider("shade", "own"),
                TestProvider("shade", "packed"),
                TestProvider("shade", "standard"),
            )
            when (pack) {
                Pack.Names -> items += TestPack("chosen", mapOf("shade" to "packed"))
                Pack.Omits -> items += TestPack("chosen", mapOf("other" to "packed"))
                Pack.NamesMissing -> items += TestPack("chosen", mapOf("shade" to "gone"))
                Pack.Unchosen, Pack.Unknown -> Unit
            }
            when (default) {
                Default.Names -> items += TestPack(LookApi.DEFAULT_PACK, mapOf("shade" to "standard"))
                Default.Omits -> items += TestPack(LookApi.DEFAULT_PACK, emptyMap())
                Default.Absent -> Unit
            }
            val choice = choice(
                pack = when (pack) {
                    Pack.Unchosen -> null
                    Pack.Unknown -> "nowhere"
                    else -> "chosen"
                },
                overrides = when (override) {
                    Override.None -> emptyMap()
                    Override.Present -> mapOf("shade" to "own")
                    Override.Missing -> mapOf("shade" to "gone")
                },
            )
            val expected = when {
                override == Override.Present -> "shade/own" to LookSource.Override
                pack == Pack.Names -> "shade/packed" to LookSource.Pack
                default == Default.Names -> "shade/standard" to LookSource.DefaultPack
                else -> "base" to LookSource.Base
            }
            val case = "override $override, pack $pack, default $default"
            val managed = Managed(choice, taken(*items.toTypedArray()))
            assertEquals(expected.first, managed.answers()["shade"], case)
            assertEquals(expected.second, managed.manager.state.value.axis("shade")?.source, case)
            assertEquals(if (expected.second == LookSource.Base) null else expected.first.substringAfter('/'), managed.manager.state.value.axis("shade")?.family, case)
            managed.manager.close()
        }
    }

    @Test
    fun `choosing the default pack by name answers from it as the chosen pack`() {
        val managed = Managed(
            choice(pack = LookApi.DEFAULT_PACK),
            taken(TestAxis("shade"), TestProvider("shade", "standard"), TestPack(LookApi.DEFAULT_PACK, mapOf("shade" to "standard"))),
        )
        assertEquals("shade/standard", managed.answers()["shade"])
        assertEquals(LookSource.Pack, managed.manager.state.value.axis("shade")?.source)
    }

    @Test
    fun `an incomplete pack leaves the axes it does not name to the default pack, and to the base after that`() {
        val managed = Managed(
            choice(pack = "partial"),
            taken(
                TestAxis("shade"), TestAxis("motion"), TestAxis("sound"),
                TestProvider("shade", "partial"), TestProvider("motion", "standard"),
                TestPack("partial", mapOf("shade" to "partial")),
                TestPack(LookApi.DEFAULT_PACK, mapOf("motion" to "standard")),
            ),
        )
        assertEquals(mapOf("shade" to "shade/partial", "motion" to "motion/standard", "sound" to "base"), managed.answers())
        val sources = managed.manager.state.value.axes.associate { it.id to it.source }
        assertEquals(mapOf("shade" to LookSource.Pack, "motion" to LookSource.DefaultPack, "sound" to LookSource.Base), sources)
    }

    @Test
    fun `a pack naming a provider no module brings passes the axis on, and the provider answers once its module comes`() {
        val axis = TestAxis("shade")
        val pack = TestPack("chosen", mapOf("shade" to "later"))
        val standard = TestProvider("shade", "standard")
        val managed = Managed(choice(pack = "chosen"), taken(axis, pack, standard, TestPack(LookApi.DEFAULT_PACK, mapOf("shade" to "standard"))))
        assertEquals("shade/standard", managed.answers()["shade"])
        assertEquals(choice(pack = "chosen"), managed.manager.state.value.choice, "the choice stays as made")

        val later = TestProvider("shade", "later")
        managed.taken.value = managed.taken.value + taken(later, module = "test.later")
        assertEquals("shade/later", managed.answers()["shade"])
        assertEquals(1, standard.closed, "the stand in is let go of")
    }

    @Test
    fun `a second axis, family or pack of one id is refused, and the first module's stays`() {
        val first = TestProvider("shade", "nx")
        val second = TestProvider("shade", "nx")
        val managed = Managed(
            choice(overrides = mapOf("shade" to "nx")),
            taken(TestAxis("shade"), first, TestPack("p", mapOf("shade" to "nx"))) +
                taken(TestAxis("shade"), second, TestPack("p", emptyMap()), module = "test.other"),
        )
        assertEquals(1, first.opened)
        assertEquals(0, second.opened)
        val state = managed.manager.state.value
        assertEquals(1, state.axes.size)
        assertEquals(listOf("test.look"), state.providers.map { it.moduleId })
        assertEquals(mapOf("shade" to "nx"), state.packs.single().providers)
    }

    @Test
    fun `only a module compiled in may bring the default pack, so no jar becomes everyone's default`() {
        val standard = TestProvider("shade", "standard")
        val jars = TestProvider("shade", "jar")
        val managed = Managed(
            choice(),
            taken(TestAxis("shade"), standard, jars) +
                taken(TestPack(LookApi.DEFAULT_PACK, mapOf("shade" to "jar")), module = "someone.jar") +
                taken(TestPack(LookApi.DEFAULT_PACK, mapOf("shade" to "standard")), module = "nexira.look.packs"),
            compiled = setOf("test.look", "nexira.look.packs"),
        )
        assertEquals("shade/standard", managed.answers()["shade"])
        assertEquals(listOf("nexira.look.packs"), managed.manager.state.value.packs.map { it.moduleId })
        assertEquals(0, jars.opened)
    }

    @Test
    fun `a provider built against a contract its axis does not serve is refused`() {
        val newer = TestProvider("shade", "newer", contract = ApiVersion(1, 3))
        val older = TestProvider("shade", "older", contract = ApiVersion(1, 0))
        val otherMajor = TestProvider("shade", "other", contract = ApiVersion(2, 0))
        val managed = Managed(choice(overrides = mapOf("shade" to "newer")), taken(TestAxis("shade", ApiVersion(1, 2)), newer, older, otherMajor))
        assertEquals("base", managed.answers()["shade"], "a provider of a newer minor may ask what this contract lacks")
        assertEquals(listOf("older"), managed.manager.state.value.providers.map { it.family })
        managed.choice.value = choice(overrides = mapOf("shade" to "older"))
        assertEquals("shade/older", managed.answers()["shade"])
        assertEquals(0, newer.opened + otherMajor.opened)
    }
}
