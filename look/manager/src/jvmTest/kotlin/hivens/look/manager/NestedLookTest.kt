package hivens.look.manager

import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withRunningRecomposer
import hivens.look.manager.api.LookApi
import hivens.look.manager.api.LookChoice
import hivens.look.manager.api.LookProvision
import hivens.look.manager.api.OverrideLook
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The look reaches the composition through the one value the host wraps it in, and a subtree can ask for
 * the look the person would have if they chose another.
 */
class NestedLookTest {

    private val shadeAxis = TestAxis("shade")
    private val motionAxis = TestAxis("motion")
    private val standardShade = TestProvider("shade", "standard")
    private val standardMotion = TestProvider("motion", "standard")
    private val materialShade = TestProvider("shade", "material")
    private val ownShade = TestProvider("shade", "own")
    private val ownMotion = TestProvider("motion", "own")
    private val fluentMotion = TestProvider("motion", "fluent")

    private fun managed(person: LookChoice = choice()) = Managed(
        person,
        taken(
            shadeAxis, motionAxis, standardShade, standardMotion, materialShade, ownShade, ownMotion, fluentMotion,
            TestPack(LookApi.DEFAULT_PACK, mapOf("shade" to "standard", "motion" to "standard")),
            TestPack("material", mapOf("shade" to "material")),
            TestPack("fluent", mapOf("motion" to "fluent")),
        ),
    )

    /** What the composition reads of both axes inside [wrap], as `shade|motion`. */
    private fun read(wrap: @Composable (@Composable () -> Unit) -> Unit): List<String> {
        val seen = mutableListOf<String>()
        composeOnce { wrap { seen += "${shadeAxis.local.current.name}|${motionAxis.local.current.name}" } }
        return seen
    }

    @Test
    fun `the host's one wrap provides every axis's answers`() {
        val managed = managed()
        assertEquals(listOf("shade/standard|motion/standard"), read { content -> managed.manager.provision.value.Provide(content) })
    }

    @Test
    fun `without a manager every axis reads its base answers`() {
        assertEquals(listOf("base|base"), read { content -> LookProvision.NONE.Provide(content) })
    }

    @Test
    fun `a subtree gets what the person would get by choosing its look, their own families included`() {
        val person = choice(pack = "fluent", overrides = mapOf("shade" to "own"))
        val managed = managed(person)
        val preview = read { content ->
            managed.manager.provision.value.Provide { OverrideLook(choice(pack = "material"), content) }
        }
        managed.choice.value = person.withPack("material")
        val chosen = read { content -> managed.manager.provision.value.Provide(content) }
        assertEquals(chosen, preview)
        assertEquals(listOf("shade/own|motion/standard"), preview, "the person's family for shade wins over the pack, and motion goes to the default pack")
    }

    @Test
    fun `a subtree's own family wins over the person's, and a subtree in a subtree lays its choice over the one around it`() {
        val managed = managed(choice(pack = "fluent", overrides = mapOf("shade" to "own")))
        val seen = read { content ->
            managed.manager.provision.value.Provide {
                OverrideLook(choice(pack = "material", overrides = mapOf("shade" to "material"))) {
                    OverrideLook(choice(overrides = mapOf("motion" to "own")), content)
                }
            }
        }
        assertEquals(listOf("shade/material|motion/own"), seen)
    }

    @Test
    fun `a subtree's providers are open while it is drawn and closed when it is gone`() {
        val managed = managed()
        var openWhileDrawn = false
        composeOnce {
            managed.manager.provision.value.Provide {
                OverrideLook(choice(pack = "material")) { openWhileDrawn = materialShade.isOpen }
            }
        }
        assertTrue(openWhileDrawn)
        assertFalse(materialShade.isOpen, "closed with the subtree")
        assertTrue(standardShade.isOpen, "the look around it stays open")
    }

    @Test
    fun `a subtree whose composition is abandoned lets go of its providers`() {
        val managed = managed()
        assertFailsWith<IllegalStateException> {
            composeOnce {
                managed.manager.provision.value.Provide {
                    OverrideLook(choice(pack = "material")) { error("the composition fails here") }
                }
            }
        }
        assertEquals(1, materialShade.opened)
        assertFalse(materialShade.isOpen, "a lease the composition abandoned is closed")
    }

    @Test
    fun `a subtree asking for what the look already uses shares the open provider`() {
        val managed = managed()
        val lease = managed.manager.lease(choice(overrides = mapOf("shade" to "standard")))
        assertEquals(listOf("shade/standard", "motion/standard"), lease.answers.value.map { (it.value as Shade).name })
        lease.close()
        assertEquals(1, standardShade.opened)
        assertTrue(standardShade.isOpen, "still the look's")
    }

    @Test
    fun `answers worked out before the boot provide a subtree nothing of its own`() {
        val managed = managed()
        val detached = managed.manager.detachedProvision()
        val seen = read { content -> detached.Provide { OverrideLook(choice(pack = "material"), content) } }
        assertEquals(listOf("shade/standard|motion/standard"), seen)
        assertEquals(0, materialShade.opened)
    }

    @Test
    fun `an answer that changes recomposes what reads its axis and nothing else`() {
        val managed = managed(choice(overrides = mapOf("shade" to "own")))
        var leaf = 0
        var reader = 0
        var read = ""
        val clock = BroadcastFrameClock()
        runBlocking(clock) {
            withRunningRecomposer { recomposer ->
                val composition = Composition(NothingApplier, recomposer)
                composition.setContent {
                    val look by managed.manager.provision.collectAsState()
                    look.Provide {
                        Counted { leaf++ }
                        Counted {
                            reader++
                            read = shadeAxis.local.current.name
                        }
                    }
                }
                repeat(3) { frame(clock) }
                val (leafBefore, readerBefore) = leaf to reader
                ownShade.answers.value = Shade("changed")
                repeat(5) { frame(clock) }
                assertEquals("changed", read)
                assertEquals(leafBefore, leaf, "a leaf that reads no axis is not composed again")
                assertTrue(reader > readerBefore, "the reader of the axis is")
                composition.dispose()
            }
        }
    }

    @Test
    fun `an axis loaded again is provided again, even with equal answers`() {
        val first = TestAxis("shade")
        val managed = Managed(choice(overrides = mapOf("shade" to "x")), taken(first, TestProvider("shade", "x"), module = "jar.shade"))
        var seenFirst = ""
        composeOnce { managed.manager.provision.value.Provide { seenFirst = first.local.current.name } }
        val again = TestAxis("shade")
        managed.taken.value = taken(again, TestProvider("shade", "x"), module = "jar.shade")
        var seenAgain = ""
        composeOnce { managed.manager.provision.value.Provide { seenAgain = again.local.current.name } }
        assertEquals("shade/x", seenFirst)
        assertEquals("shade/x", seenAgain, "the reloaded axis's own local carries the answers")
    }

    // Without a toolkit nothing tells the recomposer that state changed, so a frame does it first.
    private suspend fun frame(clock: BroadcastFrameClock) {
        yield()
        Snapshot.sendApplyNotifications()
        clock.sendFrame(0L)
        yield()
    }
}

/** A restartable scope of its own around [body], so what it reads is all that recomposes it. */
@Composable
private fun Counted(body: @Composable () -> Unit) = body()
