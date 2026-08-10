package hivens.launcher

import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.core.data.PackReference
import hivens.launcher.instance.InstanceSizeService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class PackOperationServiceTest {

    private lateinit var dataDir: Path

    private val instance = PackInstance(
        id = "inst-1",
        packRef = PackReference(PackOrigin.Mirror, "Industrial", "5"),
        displayName = "Industrial",
        instanceDirName = "industrial",
        createdAtEpoch = 0L,
    )

    @BeforeTest
    fun setUp() {
        dataDir = Files.createTempDirectory("pack-operation-test")
        val dir = Files.createDirectories(dataDir.resolve("instances").resolve("industrial"))
        Files.write(dir.resolve("pack.jar"), ByteArray(700))
    }

    @OptIn(ExperimentalPathApi::class)
    @AfterTest
    fun tearDown() {
        if (::dataDir.isInitialized) runCatching { dataDir.deleteRecursively() }
    }

    @Test
    fun `progress and outcome are published under the instance`() = runTest {
        val service = service()

        val started = service.start(instance, PackOperationKind.Repair) { progress ->
            progress(1, 2, "mods/a.jar")
            PackOperationPhase.Repaired(checked = 2, repaired = 1)
        }
        advanceUntilIdle()

        assertTrue(started)
        val operation = service.operations.value[instance.id]
        assertEquals(PackOperationKind.Repair, operation?.kind)
        val phase = operation?.phase
        assertIs<PackOperationPhase.Repaired>(phase)
        assertEquals(2, phase.checked)
        assertEquals(1, phase.repaired)
    }

    @Test
    fun `a second start while one is in flight is refused`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val runs = AtomicInteger(0)
        val service = service()

        val first = service.start(instance, PackOperationKind.Repair) {
            runs.incrementAndGet()
            gate.await()
            PackOperationPhase.Repaired(1, 0)
        }
        advanceUntilIdle()
        val second = service.start(instance, PackOperationKind.Update) {
            runs.incrementAndGet()
            PackOperationPhase.Updated("6")
        }
        advanceUntilIdle()

        assertTrue(first)
        assertFalse(second, "the instance already has an operation in flight")
        assertEquals(1, runs.get())
        assertEquals(PackOperationKind.Repair, service.operations.value[instance.id]?.kind, "the running one keeps the footer")

        gate.complete(Unit)
        advanceUntilIdle()
        assertIs<PackOperationPhase.Repaired>(service.operations.value[instance.id]?.phase)
    }

    @Test
    fun `a finished operation lets the next one start`() = runTest {
        val service = service()

        service.start(instance, PackOperationKind.Repair) { PackOperationPhase.Repaired(1, 0) }
        advanceUntilIdle()
        val second = service.start(instance, PackOperationKind.Update) { PackOperationPhase.Updated("6") }
        advanceUntilIdle()

        assertTrue(second)
        assertIs<PackOperationPhase.Updated>(service.operations.value[instance.id]?.phase)
    }

    @Test
    fun `a throwing operation lands in Failed carrying the message`() = runTest {
        val service = service()

        service.start(instance, PackOperationKind.Update) { throw IOException("mirror unreachable") }
        advanceUntilIdle()

        val phase = service.operations.value[instance.id]?.phase
        assertIs<PackOperationPhase.Failed>(phase)
        assertEquals("mirror unreachable", phase.message)
    }

    @Test
    fun `dismiss evicts a finished operation but not a running one`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val service = service()

        service.start(instance, PackOperationKind.Repair) {
            gate.await()
            PackOperationPhase.Repaired(1, 0)
        }
        advanceUntilIdle()
        service.dismiss(instance.id)
        assertIs<PackOperationPhase.Running>(service.operations.value[instance.id]?.phase, "closing a window does not end the work")

        gate.complete(Unit)
        advanceUntilIdle()
        service.dismiss(instance.id)
        assertNull(service.operations.value[instance.id])
    }

    @Test
    fun `the instance is measured again once the operation is done`() = runTest {
        val sizes = sizes()
        val service = PackOperationService(scope = this, sizes = sizes)

        service.start(instance, PackOperationKind.Repair) { PackOperationPhase.Repaired(1, 0) }
        advanceUntilIdle()

        assertEquals(700L, sizes.sizes.value[instance.id]?.bytes, "an operation that rewrote files invalidates the measured size")
    }

    private fun TestScope.sizes() = InstanceSizeService(
        dataDir = dataDir,
        scope = this,
        ioDispatcher = StandardTestDispatcher(testScheduler),
    )

    private fun TestScope.service() = PackOperationService(scope = this, sizes = sizes())
}
