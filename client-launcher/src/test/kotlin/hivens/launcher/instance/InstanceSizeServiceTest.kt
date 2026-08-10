package hivens.launcher.instance

import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.core.data.PackReference
import hivens.core.net.BlockMapStore
import hivens.test.TestClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(ExperimentalCoroutinesApi::class)
class InstanceSizeServiceTest {

    private lateinit var dataDir: Path
    private lateinit var instanceDir: Path

    private val instance = PackInstance(
        id = "inst-1",
        packRef = PackReference(PackOrigin.Mirror, "Industrial", "5"),
        displayName = "Industrial",
        instanceDirName = "industrial",
        createdAtEpoch = 0L,
    )

    @BeforeTest
    fun setUp() {
        dataDir = Files.createTempDirectory("instance-size-test")
        instanceDir = Files.createDirectories(dataDir.resolve("instances").resolve("industrial"))
    }

    @OptIn(ExperimentalPathApi::class)
    @AfterTest
    fun tearDown() {
        if (::dataDir.isInitialized) runCatching { dataDir.deleteRecursively() }
    }

    private fun write(relative: String, bytes: Int) {
        val file = instanceDir.resolve(relative)
        Files.createDirectories(file.parent)
        Files.write(file, ByteArray(bytes))
    }

    @Test
    fun `sums the pack's own files`() = runTest {
        write("mods/a.jar", 1000)
        write("config/b.cfg", 24)
        val service = service()

        service.measure(instance)
        advanceUntilIdle()

        assertEquals(1024L, service.sizes.value[instance.id]?.bytes)
    }

    @Test
    fun `leaves out the launcher's own bookkeeping`() = runTest {
        write("mods/a.jar", 500)
        write("mods/${BlockMapStore.DIR_NAME}/a.jar.blocks", 4000)
        write("mods/b.jar.part", 8000)
        write("mods/b.jar.part.state", 300)
        val service = service()

        service.measure(instance)
        advanceUntilIdle()

        assertEquals(500L, service.sizes.value[instance.id]?.bytes, "block maps and staging files are not the pack")
    }

    @Test
    fun `a fresh measurement is served without walking again`() = runTest {
        write("mods/a.jar", 100)
        val clock = TestClock(1_000L)
        val service = service(clock)

        service.measure(instance)
        advanceUntilIdle()
        write("mods/b.jar", 900)
        clock.advance(InstanceSizeService.DEFAULT_FRESH_FOR_MS - 1)
        service.measure(instance)
        advanceUntilIdle()

        assertEquals(100L, service.sizes.value[instance.id]?.bytes, "the tree is not re-walked while the last answer holds")
    }

    @Test
    fun `an old measurement is taken again`() = runTest {
        write("mods/a.jar", 100)
        val clock = TestClock(1_000L)
        val service = service(clock)

        service.measure(instance)
        advanceUntilIdle()
        write("mods/b.jar", 900)
        clock.advance(InstanceSizeService.DEFAULT_FRESH_FOR_MS)
        service.measure(instance)
        advanceUntilIdle()

        assertEquals(1000L, service.sizes.value[instance.id]?.bytes)
    }

    @Test
    fun `force re-measures a still-fresh instance`() = runTest {
        write("mods/a.jar", 100)
        val service = service()

        service.measure(instance)
        advanceUntilIdle()
        write("mods/b.jar", 900)
        service.measure(instance, force = true)
        advanceUntilIdle()

        assertEquals(1000L, service.sizes.value[instance.id]?.bytes)
    }

    @Test
    fun `an instance that is gone is forgotten`() = runTest {
        write("mods/a.jar", 100)
        val service = service()

        service.measure(instance)
        advanceUntilIdle()
        service.forget(instance.id)

        assertNull(service.sizes.value[instance.id])
    }

    @Test
    fun `a missing instance dir measures as nothing`() = runTest {
        val service = service()

        service.measure(instance.copy(instanceDirName = "never-installed"))
        advanceUntilIdle()

        assertEquals(0L, service.sizes.value[instance.id]?.bytes)
    }

    private fun TestScope.service(clock: TestClock = TestClock(0L)) =
        InstanceSizeService(
            dataDir = dataDir,
            scope = this,
            clock = clock,
            ioDispatcher = StandardTestDispatcher(testScheduler),
        )
}
