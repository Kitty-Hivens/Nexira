package hivens.look.manager

import hivens.look.manager.api.LookApi
import hivens.look.manager.api.LookCapability
import hivens.look.manager.api.LookChoice
import hivens.look.manager.api.LookVariant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A setting for an optional feature is offered only while the provider answering its axis declares the feature. */
class CapabilitiesTest {

    @Test
    fun `the feature is offered while a provider that declares it answers, and not after`() {
        val glass = TestProvider("surface", "glass", capabilities = setOf(LookCapability.BLUR, LookCapability.VARIANTS))
        val flat = TestProvider("surface", "flat")
        val managed = Managed(
            choice(pack = "glassy"),
            taken(TestAxis("surface"), glass, flat, TestPack("glassy", mapOf("surface" to "glass")), TestPack("flat", mapOf("surface" to "flat"))),
        )
        val state = { managed.manager.state.value }
        assertTrue(state().offers("surface", LookCapability.BLUR))
        assertTrue(state().offers("surface", LookCapability.VARIANTS))
        assertFalse(state().offers("surface", LookCapability.EXTENSIBLE))

        managed.choice.value = choice(pack = "flat")
        assertFalse(state().offers("surface", LookCapability.BLUR), "the flat provider declares no blur, so no blur setting")

        managed.choice.value = choice(pack = "nowhere")
        assertFalse(state().offers("surface", LookCapability.BLUR), "base answers declare nothing")
    }

    @Test
    fun `a provider that failed offers nothing, even what it declares`() {
        val broken = TestProvider("surface", "glass", capabilities = setOf(LookCapability.BLUR), failing = IllegalStateException("broken"))
        val managed = Managed(choice(overrides = mapOf("surface" to "glass")), taken(TestAxis("surface"), broken))
        assertFalse(managed.manager.state.value.offers("surface", LookCapability.BLUR))
    }

    @Test
    fun `what is on offer lists every provider and pack with what each declares`() {
        val managed = Managed(
            choice(),
            taken(TestAxis("surface"), TestProvider("surface", "glass", capabilities = setOf(LookCapability.BLUR)), TestPack(LookApi.DEFAULT_PACK, emptyMap())),
        )
        val state = managed.manager.state.value
        assertEquals(setOf(LookCapability.BLUR), state.providers.single().capabilities)
        assertEquals(LookApi.DEFAULT_PACK, state.packs.single().id)
    }

    @Test
    fun `the variant and the blur reach only the providers that declare them, and follow the choice without reopening`() {
        val glass = TestProvider("surface", "glass", capabilities = setOf(LookCapability.VARIANTS, LookCapability.BLUR))
        val plain = TestProvider("motion", "plain")
        val managed = Managed(
            LookChoice.build {
                pack = "p"
                variant = LookVariant.Light
                blur = false
            },
            taken(TestAxis("surface"), TestAxis("motion"), glass, plain, TestPack("p", mapOf("surface" to "glass", "motion" to "plain"))),
        )
        val glassContext = requireNotNull(glass.context)
        val plainContext = requireNotNull(plain.context)
        assertEquals(LookVariant.Light, glassContext.variant.value)
        assertEquals(false, glassContext.blur.value)
        assertEquals(null, plainContext.variant.value, "a provider without variants is never handed one")
        assertEquals(null, plainContext.blur.value)
        assertTrue(managed.manager.state.value.offers(LookCapability.VARIANTS), "the manager offers the variant while a provider has variants")

        managed.choice.value = managed.choice.value.withVariant(LookVariant.Dark).withBlur(null)
        assertEquals(LookVariant.Dark, glassContext.variant.value)
        assertEquals(null, glassContext.blur.value, "left to the provider")
        assertEquals(null, plainContext.variant.value)
        assertEquals(1, glass.opened, "a feature changes in place, and the provider stays open")
    }

    @Test
    fun `no setting is offered for a feature no provider in use declares`() {
        val managed = Managed(choice(pack = "p"), taken(TestAxis("motion"), TestProvider("motion", "plain"), TestPack("p", mapOf("motion" to "plain"))))
        assertFalse(managed.manager.state.value.offers(LookCapability.VARIANTS))
        assertFalse(managed.manager.state.value.offers(LookCapability.BLUR))
    }
}
