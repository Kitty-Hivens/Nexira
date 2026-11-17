package hivens.look.manager

import hivens.look.manager.api.LookApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** The look outside composition is the look the composition is provided, at every step. */
class SnapshotTest {

    private val shade = TestAxis("shade")
    private val motion = TestAxis("motion")
    private val own = TestProvider("shade", "own")
    private val standard = TestProvider("motion", "standard")

    private fun managed() = Managed(
        choice(overrides = mapOf("shade" to "own")),
        taken(shade, motion, own, standard, TestPack(LookApi.DEFAULT_PACK, mapOf("motion" to "standard"))),
    )

    /** What a composition inside the manager's provision reads of both axes. */
    private fun provided(managed: Managed): Map<String, Shade> {
        var seen = emptyMap<String, Shade>()
        composeOnce { managed.manager.provision.value.Provide { seen = mapOf("shade" to shade.local.current, "motion" to motion.local.current) } }
        return seen
    }

    /** What the snapshot outside composition holds of both axes. */
    private fun snapshot(managed: Managed): Map<String, Shade> {
        val now = managed.manager.view.answers.value
        return mapOf("shade" to now.of(shade), "motion" to now.of(motion))
    }

    @Test
    fun `the snapshot is what the provision provides, as the answers and the choice change`() {
        val managed = managed()
        assertEquals(mapOf("shade" to Shade("shade/own"), "motion" to Shade("motion/standard")), snapshot(managed))
        assertEquals(provided(managed), snapshot(managed))

        own.answers.value = Shade("darker")
        assertEquals(Shade("darker"), snapshot(managed)["shade"], "a provider's new answers reach it without a new choice")
        assertEquals(provided(managed), snapshot(managed))

        managed.choice.value = choice()
        assertEquals(Shade("base"), snapshot(managed)["shade"], "an axis nothing answers is its base answers")
        assertEquals(provided(managed), snapshot(managed))
    }

    @Test
    fun `the view's state is the manager's state`() {
        val managed = managed()
        assertSame(managed.manager.state, managed.manager.view.state)
    }
}
