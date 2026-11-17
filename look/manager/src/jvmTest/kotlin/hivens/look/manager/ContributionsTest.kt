@file:OptIn(HostApi::class)

package hivens.look.manager

import hivens.look.manager.api.LookAnswers
import hivens.look.manager.api.LookApi
import hivens.look.manager.api.LookContext
import hivens.look.manager.api.LookProvider
import hivens.widget.api.HostApi
import hivens.widget.api.ModuleContribution
import hivens.widget.api.TakenContribution
import hivens.widget.api.TakenContributions
import hivens.widget.model.ApiVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** Axes, providers and packs come and go with the modules that bring them. */
class ContributionsTest {

    @Test
    fun `an axis a module adds is answered the moment it arrives, and leaves with its module`() {
        val managed = Managed(choice(), taken(TestAxis("shade")), compiled = setOf("test.look", "someone.wobble"))
        assertEquals(mapOf("shade" to "base"), managed.answers())

        val wobble = TestProvider("wobble", "own")
        val module = taken(TestAxis("wobble"), wobble, TestPack(LookApi.DEFAULT_PACK, mapOf("wobble" to "own")), module = "someone.wobble")
        managed.taken.value = managed.taken.value + module
        assertEquals(mapOf("shade" to "base", "wobble" to "wobble/own"), managed.answers())
        assertEquals(listOf("shade", "wobble"), managed.manager.state.value.axes.map { it.id })

        managed.taken.value = managed.taken.value - module.toSet()
        assertEquals(mapOf("shade" to "base"), managed.answers())
        assertNull(managed.manager.state.value.axis("wobble"))
        assertFalse(wobble.isOpen, "its provider is closed with it")
    }

    @Test
    fun `a module loaded again is a new provider, opened afresh and the old one closed`() {
        val before = TestProvider("shade", "nx")
        val managed = Managed(choice(overrides = mapOf("shade" to "nx")), taken(TestAxis("shade"), before))
        val after = TestProvider("shade", "nx")
        managed.taken.value = taken(TestAxis("shade"), after)
        assertFalse(before.isOpen)
        assertEquals(1, after.opened)
    }

    @Test
    fun `the document names only the manager, and the look modules compiled in still reach it`() {
        val standard = TestProvider("shade", "standard")
        val material = TestProvider("shade", "material")
        // The document names none of these modules, so the host's list of what the named ones carry is
        // empty, and every compiled module's contributions are beside it.
        val compiled = taken(TestAxis("shade"), standard, TestPack(LookApi.DEFAULT_PACK, mapOf("shade" to "standard")), module = "nexira.look.shade") +
            taken(material, TestPack("material", mapOf("shade" to "material")), module = "nexira.look.material")
        val jar = TestProvider("shade", "jar")
        val jars = MutableStateFlow<List<TakenContribution<ModuleContribution>>>(emptyList())
        val host = object : TakenContributions {
            override val all: StateFlow<List<TakenContribution<ModuleContribution>>> = jars
            override val compiledIn: List<TakenContribution<ModuleContribution>> = compiled
        }
        val scope = eagerScope()
        val choice = MutableStateFlow(choice())
        val manager = LookManager(choice, lookTaken(host, scope), ::ModuleServices, scope, setOf("nexira.look.shade", "nexira.look.material"), Dispatchers.Unconfined, Dispatchers.Unconfined)
        fun answer() = manager.answers.value.single().value

        assertEquals(Shade("shade/standard"), answer())
        assertEquals(0, material.opened, "on offer, and not opened")

        choice.value = choice(pack = "material")
        assertEquals(Shade("shade/material"), answer())

        jars.value = taken(jar, module = "someone.jar")
        choice.value = choice(overrides = mapOf("shade" to "jar"))
        assertEquals(Shade("shade/jar"), answer(), "a jar switched on brings its provider too")
        manager.close()
    }

    @Test
    fun `a provider that failed is tried again once its module leaves and comes back`() {
        var attempts = 0
        val healing = object : LookProvider<Shade> {
            override val label = "healing"
            override val axis = "shade"
            override val family = "healing"
            override val contract = ApiVersion(1, 0)
            override fun open(context: LookContext): LookAnswers<Shade> {
                attempts++
                check(attempts > 1) { "not ready on the first try" }
                return LookAnswers.of(Shade("healed"))
            }
        }
        val axis = TestAxis("shade")
        val managed = Managed(choice(overrides = mapOf("shade" to "healing")), taken(axis, healing))
        assertEquals("base", managed.answers()["shade"])
        managed.taken.value = taken(axis)
        managed.taken.value = taken(axis, healing)
        assertEquals("healed", managed.answers()["shade"], "the same contribution brought again is a fresh start")
    }
}
