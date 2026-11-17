package hivens.look.manager

import hivens.look.manager.api.LookAnswers
import hivens.look.manager.api.LookApi
import hivens.look.manager.api.LookContext
import hivens.look.manager.api.LookProvider
import hivens.look.manager.api.LookSource
import hivens.widget.model.ApiVersion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.Collections
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The manager opens only the providers the choice needs, closes the ones it stops needing, and a failing one costs only itself. */
class LoadingTest {

    @Test
    fun `only the providers the choice names are opened, once each`() {
        val chosen = TestProvider("shade", "nx")
        val idle = listOf(TestProvider("shade", "material"), TestProvider("shade", "fluent"), TestProvider("motion", "material"))
        val standardMotion = TestProvider("motion", "nx")
        Managed(
            choice(pack = "nexira-like"),
            taken(
                TestAxis("shade"), TestAxis("motion"), chosen, standardMotion, *idle.toTypedArray(),
                TestPack("nexira-like", mapOf("shade" to "nx", "motion" to "nx")),
                TestPack("material", mapOf("shade" to "material", "motion" to "material")),
            ),
        )
        assertEquals(1, chosen.opened)
        assertEquals(1, standardMotion.opened)
        idle.forEach { assertEquals(0, it.opened, "${it.id} is not in the choice and is never opened") }
    }

    @Test
    fun `switching the choice live closes the provider it switched away from and opens the new one`() {
        val nx = TestProvider("shade", "nx")
        val material = TestProvider("shade", "material")
        val managed = Managed(
            choice(pack = "nx"),
            taken(TestAxis("shade"), nx, material, TestPack("nx", mapOf("shade" to "nx")), TestPack("material", mapOf("shade" to "material"))),
        )
        assertEquals("shade/nx", managed.answers()["shade"])

        managed.choice.value = choice(pack = "material")
        assertEquals("shade/material", managed.answers()["shade"])
        assertFalse(nx.isOpen, "the provider switched away from is closed")
        assertTrue(material.isOpen)
        assertEquals(1, material.opened)

        managed.choice.value = choice(pack = "material", overrides = mapOf("shade" to "nx"))
        assertEquals("shade/nx", managed.answers()["shade"])
        assertEquals(2, nx.opened, "opened again when chosen again")
        assertFalse(material.isOpen)
    }

    @Test
    fun `a provider's new answers reach the look without a new choice`() {
        val nx = TestProvider("shade", "nx")
        val managed = Managed(choice(overrides = mapOf("shade" to "nx")), taken(TestAxis("shade"), nx))
        nx.answers.value = Shade("darker")
        assertEquals("darker", managed.answers()["shade"])
    }

    @Test
    fun `a provider that throws anything while opening is passed over, and every axis goes on down the choice`() {
        val failures = listOf<Throwable>(
            IllegalStateException("broken"),
            NoClassDefFoundError("hivens/look/Gone"),
            NotImplementedError("not answered yet"),
            AssertionError("no"),
            StackOverflowError(),
            CancellationException("its own timeout"),
        )
        failures.forEach { failure ->
            val broken = TestProvider("shade", "broken", failing = failure)
            val standard = TestProvider("shade", "standard")
            val motion = TestProvider("motion", "nx")
            val managed = Managed(
                choice(pack = "chosen"),
                taken(
                    TestAxis("shade"), TestAxis("motion"), broken, standard, motion,
                    TestPack("chosen", mapOf("shade" to "broken", "motion" to "nx")), TestPack(LookApi.DEFAULT_PACK, mapOf("shade" to "standard")),
                ),
            )
            val what = failure::class.simpleName
            assertEquals(mapOf("shade" to "shade/standard", "motion" to "motion/nx"), managed.answers(), "$what costs only the provider")
            assertEquals(LookSource.DefaultPack, managed.manager.state.value.axis("shade")?.source, what)
            assertTrue(motion.isOpen, "$what: the other axes stay answered")

            managed.choice.value = choice(pack = "chosen", overrides = mapOf("shade" to "standard"))
            managed.choice.value = choice(pack = "chosen")
            assertEquals(1, broken.opened, "$what: a provider that failed is not tried again while the same contribution is brought")
            managed.manager.close()
        }
    }

    @Test
    fun `a provider whose open times out keeps the manager following the choice`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val choice = MutableStateFlow(choice())
        val slow = object : LookProvider<Shade> {
            override val label = "slow"
            override val axis = "shade"
            override val family = "slow"
            override val contract = ApiVersion(1, 0)
            override fun open(context: LookContext): LookAnswers<Shade> {
                runBlocking { withTimeout(20) { delay(1000) } }
                return LookAnswers.of(Shade("slow"))
            }
        }
        val nx = TestProvider("shade", "nx")
        val manager = LookManager(choice, MutableStateFlow(taken(TestAxis("shade"), slow, nx)), ::ModuleServices, scope, setOf("test.look"))
        try {
            choice.value = choice(overrides = mapOf("shade" to "slow"))
            assertTrue(waitUntil { manager.state.value.choice == choice.value }, "the timed out choice is taken")
            assertEquals(LookSource.Base, manager.state.value.axis("shade")?.source, "the provider whose open timed out is passed over")
            choice.value = choice(overrides = mapOf("shade" to "nx"))
            assertTrue(waitUntil { manager.state.value.axis("shade")?.family == "nx" }, "the manager goes on following the choice")
        } finally {
            manager.close()
            scope.cancel()
        }
    }

    @Test
    fun `the manager's own cancellation ends a pass, and what that pass opened is closed`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val first = TestProvider("motion", "nx")
        val cancelling = object : LookProvider<Shade> {
            override val label = "cancelling"
            override val axis = "shade"
            override val family = "cancelling"
            override val contract = ApiVersion(1, 0)
            override fun open(context: LookContext): LookAnswers<Shade> {
                scope.cancel()
                throw CancellationException("the manager is going away")
            }
        }
        val manager = LookManager(
            MutableStateFlow(choice(pack = "p")),
            MutableStateFlow(taken(TestAxis("motion"), TestAxis("shade"), first, cancelling, TestPack("p", mapOf("motion" to "nx", "shade" to "cancelling")))),
            ::ModuleServices, scope, setOf("test.look"), worker = Dispatchers.Unconfined, providerDispatcher = Dispatchers.Unconfined,
        )
        assertEquals(1, first.opened)
        assertFalse(first.isOpen, "a provider the abandoned pass opened has no owner left, and is closed")
        manager.close()
    }

    @Test
    fun `a provider answering in another type than its axis is passed over`() {
        @Suppress("UNCHECKED_CAST")
        // The point of the test: a provider whose answers are not the axis's type, as a jar built against another contract would have.
        val stranger = object : LookProvider<Any> {
            override val label = "stranger"
            override val axis = "shade"
            override val family = "stranger"
            override val contract = ApiVersion(1, 0)
            override fun open(context: LookContext): LookAnswers<Any> = LookAnswers.of("not a shade")
        } as LookProvider<Shade>
        val managed = Managed(choice(overrides = mapOf("shade" to "stranger")), taken(TestAxis("shade"), stranger))
        assertEquals("base", managed.answers()["shade"])
        assertEquals(LookSource.Base, managed.manager.state.value.axis("shade")?.source)
    }

    @Test
    fun `a provider whose answers turn into another type is passed over from then on`() {
        val turning = MutableStateFlow<Any>(Shade("fine"))
        @Suppress("UNCHECKED_CAST")
        // Its answers start in the axis's type and stop being it, as a provider out of step with its contract might.
        val provider = object : LookProvider<Any> {
            override val label = "turning"
            override val axis = "shade"
            override val family = "turning"
            override val contract = ApiVersion(1, 0)
            override fun open(context: LookContext): LookAnswers<Any> = LookAnswers.following(turning as StateFlow<Any>)
        } as LookProvider<Shade>
        val standard = TestProvider("shade", "standard")
        val managed = Managed(
            choice(overrides = mapOf("shade" to "turning")),
            taken(TestAxis("shade"), provider, standard, TestPack(LookApi.DEFAULT_PACK, mapOf("shade" to "standard"))),
        )
        assertEquals("fine", managed.answers()["shade"])
        turning.value = "no longer a shade"
        assertEquals("shade/standard", managed.answers()["shade"])
        assertEquals(LookSource.DefaultPack, managed.manager.state.value.axis("shade")?.source)
    }

    @OptIn(ExperimentalForInheritanceCoroutinesApi::class)
    private class SecondReadFails : StateFlow<Any> {
        private var reads = 0
        override val value: Any get() = if (++reads == 1) Shade("first") else "not a shade"
        override val replayCache: List<Any> get() = listOf(value)
        override suspend fun collect(collector: FlowCollector<Any>): Nothing = awaitCancellation()
    }

    @Test
    fun `a provider that stops answering in its type within one pass is passed over in that same pass`() {
        @Suppress("UNCHECKED_CAST")
        // Answers that are the axis's type when the provider opens and not when the pass reads them.
        val flaky = object : LookProvider<Any> {
            override val label = "flaky"
            override val axis = "shade"
            override val family = "flaky"
            override val contract = ApiVersion(1, 0)
            override fun open(context: LookContext): LookAnswers<Any> = LookAnswers.following(SecondReadFails())
        } as LookProvider<Shade>
        val standard = TestProvider("shade", "standard")
        val managed = Managed(
            choice(overrides = mapOf("shade" to "flaky")),
            taken(TestAxis("shade"), flaky, standard, TestPack(LookApi.DEFAULT_PACK, mapOf("shade" to "standard"))),
        )
        assertEquals("shade/standard", managed.answers()["shade"])
        assertEquals(LookSource.DefaultPack, managed.manager.state.value.axis("shade")?.source)
    }

    @Test
    fun `providers are opened off the thread that asks, and a subtree asking returns before a slow one answers`() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val gate = CountDownLatch(1)
        val openedOn = AtomicReference<Thread>()
        val slow = object : LookProvider<Shade> {
            override val label = "slow"
            override val axis = "shade"
            override val family = "slow"
            override val contract = ApiVersion(1, 0)
            override fun open(context: LookContext): LookAnswers<Shade> {
                openedOn.set(Thread.currentThread())
                gate.await(10, TimeUnit.SECONDS)
                return LookAnswers.of(Shade("slow"))
            }
        }
        val manager = LookManager(MutableStateFlow(choice()), MutableStateFlow(taken(TestAxis("shade"), slow)), ::ModuleServices, scope, setOf("test.look"))
        try {
            val asked = System.nanoTime()
            val lease = manager.lease(choice(overrides = mapOf("shade" to "slow")))
            val took = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - asked)
            assertTrue(took < 1000, "the lease came back in $took ms, while the provider still waits")
            assertEquals(emptyList(), lease.answers.value, "until the provider answers, the subtree keeps the look around it")
            assertTrue(waitUntil { openedOn.get() != null })
            assertTrue(openedOn.get() != Thread.currentThread(), "the provider opened on the worker, not on the thread that asked")
            gate.countDown()
            assertTrue(waitUntil { lease.answers.value.any { (it.value as Shade).name == "slow" } })
            lease.close()
        } finally {
            gate.countDown()
            manager.close()
            scope.cancel()
        }
    }

    @Test
    fun `leases, choices and modules changing from many threads open each provider once at a time and leave nothing open`() {
        val problems = Collections.synchronizedList(ArrayList<String>())
        class Counting(override val axis: String, override val family: String) : LookProvider<Shade> {
            override val label = "$axis-$family"
            override val contract = ApiVersion(1, 0)
            val live = AtomicInteger()
            override fun open(context: LookContext): LookAnswers<Shade> {
                if (live.incrementAndGet() > 1) problems += "$id open twice at once"
                return object : LookAnswers<Shade> {
                    override val answers = MutableStateFlow(Shade(id))
                    override fun close() {
                        if (live.decrementAndGet() < 0) problems += "$id closed more often than opened"
                    }
                }
            }
        }
        val families = listOf("a", "b", "c", "d")
        val providers = families.map { Counting("shade", it) } + families.map { Counting("motion", it) }
        val packs = families.map { f -> TestPack(f, mapOf("shade" to f, "motion" to f)) }
        val base = taken(TestAxis("shade"), TestAxis("motion"), *providers.toTypedArray(), *packs.toTypedArray(), TestPack(LookApi.DEFAULT_PACK, mapOf("shade" to "a", "motion" to "a")))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val choice = MutableStateFlow(choice())
        val takenFlow = MutableStateFlow(base)
        val manager = LookManager(choice, takenFlow, ::ModuleServices, scope, setOf("test.look"))
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        repeat(8) { t ->
            pool.submit {
                start.await()
                val random = Random(t.toLong())
                repeat(300) {
                    when (random.nextInt(3)) {
                        0 -> choice.value = choice(pack = families[random.nextInt(4)], overrides = if (random.nextBoolean()) mapOf("shade" to families[random.nextInt(4)]) else emptyMap())
                        1 -> manager.lease(choice(pack = families[random.nextInt(4)])).close()
                        else -> takenFlow.value = ArrayList(base)
                    }
                }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS))
        choice.value = choice(pack = "d")
        assertTrue(waitUntil { manager.state.value.axis("shade")?.family == "d" && providers.count { it.live.get() > 0 } == 2 })
        manager.close()
        scope.cancel()
        assertEquals(emptyList(), problems.take(5))
        assertEquals(emptyList(), providers.filter { it.live.get() != 0 }.map { "${it.id}=${it.live.get()}" }, "closing the manager closed all it opened")
    }

    @Test
    fun `closing the manager closes every provider it opened`() {
        val shade = TestProvider("shade", "nx")
        val motion = TestProvider("motion", "nx")
        val managed = Managed(choice(pack = "p"), taken(TestAxis("shade"), TestAxis("motion"), shade, motion, TestPack("p", mapOf("shade" to "nx", "motion" to "nx"))))
        managed.manager.close()
        assertFalse(shade.isOpen)
        assertFalse(motion.isOpen)
    }
}
