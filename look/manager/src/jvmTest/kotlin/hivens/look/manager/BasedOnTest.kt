package hivens.look.manager

import hivens.look.manager.api.LookAnswers
import hivens.look.manager.api.LookApi
import hivens.look.manager.api.LookContext
import hivens.look.manager.api.LookProvider
import hivens.look.manager.api.LookSource
import hivens.look.manager.api.under
import hivens.widget.model.ApiVersion
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A provider stands on another of its axis: it takes that one's answers, and the manager keeps the one under it. */
class BasedOnTest {

    /** What was opened and closed, in order. */
    private val log = ArrayList<String>()
    private val axis = TestAxis("shade")

    /** Answers with what it stands on and its own name, or its own name where it stands on nothing. */
    private inner class Standing(override val family: String, override val basedOn: String? = null, private val failing: Throwable? = null) : LookProvider<Shade> {
        override val label = family
        override val axis = "shade"
        override val contract = ApiVersion(1, 0)
        val own = MutableStateFlow(Shade(family))
        var context: LookContext? = null

        override fun open(context: LookContext): LookAnswers<Shade> {
            log += "open $family"
            this.context = context
            failing?.let { throw it }
            val answers = if (basedOn == null) LookAnswers.following(own) else LookAnswers.over(context, this@BasedOnTest.axis) { Shade("${it.name}+$family") }
            return object : LookAnswers<Shade> by answers {
                override fun close() {
                    log += "close $family"
                    answers.close()
                }
            }
        }
    }

    private fun managed(vararg providers: LookProvider<Shade>, family: String) = Managed(choice(overrides = mapOf("shade" to family)), taken(axis, *providers))

    @Test
    fun `it answers over the one it stands on, which is opened before it and closed after it`() {
        val managed = managed(Standing("nx", basedOn = "system"), Standing("system"), family = "nx")
        assertEquals("system+nx", managed.answers()["shade"])
        assertEquals("nx", managed.manager.state.value.axis("shade")?.family, "the one on top answers the axis")
        assertEquals(listOf("open system", "open nx"), log)

        managed.choice.value = choice()
        assertEquals("base", managed.answers()["shade"])
        assertEquals(listOf("open system", "open nx", "close nx", "close system"), log)
    }

    @Test
    fun `what the one under it changes reaches the look through it`() {
        val system = Standing("system")
        val managed = managed(Standing("nx", basedOn = "system"), system, family = "nx")
        system.own.value = Shade("other")
        assertEquals("other+nx", managed.answers()["shade"])
        assertEquals(2, log.size, "nothing is opened again for it")
    }

    @Test
    fun `providers stand on each other as deep as they say`() {
        val managed = managed(Standing("c", basedOn = "b"), Standing("a"), Standing("b", basedOn = "a"), family = "c")
        assertEquals("a+b+c", managed.answers()["shade"])
        managed.manager.close()
        assertEquals(listOf("open a", "open b", "open c", "close c", "close b", "close a"), log)
    }

    @Test
    fun `where the one under it is not there it stands on the axis's base answers, and is opened again when it comes and goes`() {
        val nx = Standing("nx", basedOn = "system")
        val system = Standing("system")
        val managed = managed(nx, family = "nx")
        assertEquals("base+nx", managed.answers()["shade"])

        managed.taken.value = taken(axis, nx, system)
        assertEquals("system+nx", managed.answers()["shade"])

        managed.taken.value = taken(axis, nx)
        assertEquals("base+nx", managed.answers()["shade"])
        assertEquals(listOf("open nx", "open system", "open nx", "close nx", "open nx", "close nx", "close system"), log)
    }

    @Test
    fun `where the one under it fails to open it stands on the axis's base answers and still answers`() {
        val managed = managed(Standing("nx", basedOn = "system"), Standing("system", failing = IllegalStateException("broken")), family = "nx")
        assertEquals("base+nx", managed.answers()["shade"])
        assertEquals(LookSource.Override, managed.manager.state.value.axis("shade")?.source)
        assertEquals(listOf("open system", "open nx"), log)
    }

    @Test
    fun `where the one under it stops answering in the axis's type it stands on the base answers from then on`() {
        val turning = MutableStateFlow<Any>(Shade("fine"))
        @Suppress("UNCHECKED_CAST")
        // Its answers start in the axis's type and stop being it, as a provider out of step with its contract might.
        val system = object : LookProvider<Any> {
            override val label = "system"
            override val axis = "shade"
            override val family = "system"
            override val contract = ApiVersion(1, 0)
            override fun open(context: LookContext): LookAnswers<Any> = LookAnswers.following(turning as StateFlow<Any>)
        } as LookProvider<Shade>
        // Takes what it stands on once, so a value of another type does not reach its own code.
        val nx = object : LookProvider<Shade> {
            override val label = "nx"
            override val axis = "shade"
            override val family = "nx"
            override val contract = ApiVersion(1, 0)
            override val basedOn = "system"
            override fun open(context: LookContext) = LookAnswers.of(Shade(context.under(this@BasedOnTest.axis).value.name + "+nx"))
        }
        val managed = managed(nx, system, family = "nx")
        assertEquals("fine+nx", managed.answers()["shade"])
        turning.value = "no longer a shade"
        assertEquals("base+nx", managed.answers()["shade"])
    }

    @Test
    fun `providers that stand on each other in a circle are refused, and one that stands on them stands on the base answers`() {
        val providers = arrayOf(Standing("a", basedOn = "b"), Standing("b", basedOn = "a"), Standing("self", basedOn = "self"), Standing("out", basedOn = "a"))
        val managed = managed(*providers, family = "out")
        assertEquals("base+out", managed.answers()["shade"])
        assertEquals(listOf("out"), managed.manager.state.value.providers.map { it.family })
        listOf("a", "b", "self").forEach { family ->
            managed.choice.value = choice(overrides = mapOf("shade" to family))
            assertEquals("base", managed.answers()["shade"], family)
        }
    }

    @Test
    fun `one that answers a subtree and is stood on elsewhere is open once`() {
        val managed = managed(Standing("nx", basedOn = "system"), Standing("system"), family = "nx")
        val lease = managed.manager.lease(choice(overrides = mapOf("shade" to "system")))
        assertEquals(listOf("system"), lease.answers.value.map { (it.value as Shade).name })
        assertEquals(1, log.count { it == "open system" })

        managed.choice.value = choice()
        assertTrue("close system" !in log, "the subtree still draws with it")
        lease.close()
        assertEquals("close system", log.last())
    }

    @Test
    fun `a provider of another axis with the family's name is not what it stands on`() {
        val other = TestProvider("motion", "system")
        val managed = Managed(choice(overrides = mapOf("shade" to "nx")), taken(axis, TestAxis("motion"), Standing("nx", basedOn = "system"), other))
        assertEquals("base+nx", managed.answers()["shade"])
        assertEquals(0, other.opened)
    }

    @Test
    fun `a provider that stands on nothing is handed nothing to stand on`() {
        val alone = Standing("alone")
        managed(alone, family = "alone")
        assertNull(alone.context?.under)
        assertEquals(Shade("base"), requireNotNull(alone.context).under(axis).value)
    }

    @Test
    fun `a pack names the one on top, and the one under it is found by itself`() {
        val managed = Managed(choice(), taken(axis, Standing("nx", basedOn = "system"), Standing("system"), TestPack(LookApi.DEFAULT_PACK, mapOf("shade" to "nx"))))
        assertEquals("system+nx", managed.answers()["shade"])
        assertEquals(LookSource.DefaultPack, managed.manager.state.value.axis("shade")?.source)
    }
}
