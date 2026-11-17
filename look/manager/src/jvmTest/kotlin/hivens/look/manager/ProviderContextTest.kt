package hivens.look.manager

import hivens.look.manager.api.LookApi
import hivens.look.manager.api.LookSource
import hivens.widget.api.LevelServices
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/** A provider is opened with what its own module may use, and with nothing of another module's. */
class ProviderContextTest {

    @Test
    fun `a provider is opened with its own module's services, and with no boot context once the host is up`() {
        val shade = TestProvider("shade", "own")
        val motion = TestProvider("motion", "own")
        Managed(
            choice(pack = "both"),
            taken(TestAxis("shade"), TestAxis("motion"), TestPack("both", mapOf("shade" to "own", "motion" to "own"))) +
                taken(shade, module = "test.shade") +
                taken(motion, module = "test.motion"),
        )
        val shadeContext = requireNotNull(shade.context)
        val motionContext = requireNotNull(motion.context)
        assertEquals("test.shade", assertIs<ModuleServices>(shadeContext.services).moduleId)
        assertEquals("test.motion", assertIs<ModuleServices>(motionContext.services).moduleId, "each provider gets its own module's, not the module that brought the pack")
        assertNull(shadeContext.boot)
        assertNull(motionContext.boot)
    }

    @Test
    fun `a provider whose module's services cannot be had is passed over, and the axis goes on down the choice`() {
        val stranded = TestProvider("shade", "stranded")
        val standard = TestProvider("shade", "standard")
        val servicesOf = { moduleId: String ->
            check(moduleId != "test.stranded") { "the host has nothing for '$moduleId'" }
            ModuleServices(moduleId) as LevelServices
        }
        val managed = Managed(
            choice(overrides = mapOf("shade" to "stranded")),
            taken(TestAxis("shade"), standard, TestPack(LookApi.DEFAULT_PACK, mapOf("shade" to "standard"))) + taken(stranded, module = "test.stranded"),
            servicesOf = servicesOf,
        )
        assertEquals("shade/standard", managed.answers()["shade"])
        assertEquals(LookSource.DefaultPack, managed.manager.state.value.axis("shade")?.source)
        assertEquals(0, stranded.opened, "its code is never run without its module's services")
    }
}
