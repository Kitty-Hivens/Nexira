package hivens.ui.widgets.modules

import hivens.catalogue.CatalogueModule
import hivens.catalogue.api.PackListing
import hivens.catalogue.api.PackSourceContribution
import hivens.ui.widgets.modules.WidgetModules.Contributed
import hivens.widget.api.CompiledModule
import hivens.widget.api.KeyContribution
import hivens.widget.api.LevelServices
import hivens.widget.api.ModuleContribution
import hivens.widget.api.ModuleKeys
import hivens.widget.api.WidgetDescriptor
import hivens.widget.api.WidgetRegistry
import hivens.widget.model.WidgetKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * What the host hands the modules it trusts of the kinds the compiled modules take: a
 * source compiled in is handed over the way a jar's is, only where the document names
 * its module, and nothing of a kind no compiled module takes.
 */
class TakenContributionsHostTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() = scope.cancel()

    private object NoWidgets : WidgetRegistry {
        override fun all(): Map<WidgetKind, WidgetDescriptor> = emptyMap()
        override fun get(kind: WidgetKind): WidgetDescriptor? = null
    }

    private class Source(override val id: String) : PackSourceContribution {
        override val label: String = id
        override fun open(services: LevelServices): PackListing = error("not opened here")
    }

    private object Keys : KeyContribution {
        override val id: String = "keys"
        override val label: String = "keys"
        override fun declare(keys: ModuleKeys) = Unit
    }

    private class Compiled(override val id: String, override val contributions: List<ModuleContribution>) : CompiledModule {
        override val name: String = id
    }

    private val shelf = Source("Shelf")
    private val attic = Source("Attic")
    private val fromJar = Source("Jar")

    private fun snapshot(vararg items: Contributed<ModuleContribution>) = WidgetModules.Snapshot(NoWidgets, emptyList(), items.toList())

    @Test
    fun `a named compiled module's source and a jar's are handed over, in that order, and nothing else`() {
        // A kind nobody takes, the way a jar can carry one meant for a module that is not here.
        val other = object : ModuleContribution {
            override val id = "other"
            override val label = "other"
        }
        val jars = MutableStateFlow(snapshot(Contributed("jar", "Jar", fromJar), Contributed("jar", "Jar", other)))
        val taken = hostTakenContributions(
            named = setOf(CatalogueModule.id, "test.shelf"),
            compiled = listOf(CatalogueModule, Compiled("test.shelf", listOf(Keys, shelf)), Compiled("test.attic", listOf(attic))),
            jars = jars,
            scope = scope,
        )
        assertEquals(
            listOf("test.shelf" to shelf, "jar" to fromJar),
            taken.all.value.map { it.moduleId to it.item },
            "the attic is not named, a key contribution is the host's business, and nobody takes the other kind",
        )

        assertEquals(
            listOf("test.shelf" to shelf, "test.attic" to attic),
            taken.compiledIn.map { it.moduleId to it.item },
            "what every compiled module carries, the attic not brought in among it, so its id is kept from a jar",
        )

        jars.value = snapshot()
        val after = runBlocking { withTimeout(5.seconds) { taken.all.first { it.size == 1 } } }
        assertEquals(listOf(shelf), after.map { it.item }, "a jar switched off takes its source with it")
    }
}
