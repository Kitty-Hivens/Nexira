package hivens.look.manager

import hivens.look.manager.api.LookApi
import hivens.look.manager.api.LookPackEntry
import hivens.look.manager.api.LookProviderEntry
import hivens.look.manager.api.LookSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What the manager says of the look: everything on offer, and which family draws each axis now. */
class StateTest {

    @Test
    fun `the state lists every provider and pack on offer, in use or not`() {
        val managed = Managed(
            choice(pack = "glassy"),
            taken(TestAxis("surface"), TestProvider("surface", "glass"), TestPack("glassy", mapOf("surface" to "glass"))) +
                taken(TestProvider("surface", "flat"), TestPack(LookApi.DEFAULT_PACK, mapOf("surface" to "flat")), module = "test.other"),
        )
        val state = managed.manager.state.value
        assertEquals(
            listOf(
                LookProviderEntry("test.look", "surface", "glass", "surface:glass"),
                LookProviderEntry("test.other", "surface", "flat", "surface:flat"),
            ),
            state.providers,
            "the flat provider is not in use and is still on offer",
        )
        assertEquals(
            listOf(
                LookPackEntry("test.look", "glassy", "glassy", mapOf("surface" to "glass")),
                LookPackEntry("test.other", LookApi.DEFAULT_PACK, LookApi.DEFAULT_PACK, mapOf("surface" to "flat")),
            ),
            state.packs,
        )
    }

    @Test
    fun `a family draws its axis while the choice puts it in use, and not after`() {
        val managed = Managed(
            choice(pack = "glassy"),
            taken(
                TestAxis("surface"), TestProvider("surface", "glass"), TestProvider("surface", "flat"),
                TestPack("glassy", mapOf("surface" to "glass")), TestPack("flat", mapOf("surface" to "flat")),
            ),
        )
        val state = { managed.manager.state.value }
        assertTrue(state().draws("surface", "glass"))
        assertFalse(state().draws("surface", "flat"))

        managed.choice.value = choice(pack = "flat")
        assertFalse(state().draws("surface", "glass"), "the choice moved to another pack")
        assertTrue(state().draws("surface", "flat"))

        managed.choice.value = choice(pack = "nowhere")
        assertEquals(LookSource.Base, state().axis("surface")?.source)
        assertFalse(state().draws("surface", "glass"), "base answers are no family's")
        assertFalse(state().draws("surface", "flat"))
    }

    @Test
    fun `a family whose provider fails to open does not draw, and the one the choice falls to does`() {
        val broken = TestProvider("surface", "glass", failing = IllegalStateException("broken"))
        val managed = Managed(
            choice(pack = "flat", overrides = mapOf("surface" to "glass")),
            taken(TestAxis("surface"), broken, TestProvider("surface", "flat"), TestPack("flat", mapOf("surface" to "flat"))),
        )
        val state = managed.manager.state.value
        assertEquals(1, broken.opened, "the failing provider was tried")
        assertFalse(state.draws("surface", "glass"))
        assertTrue(state.draws("surface", "flat"))
        assertEquals(LookSource.Pack, state.axis("surface")?.source)
    }
}
