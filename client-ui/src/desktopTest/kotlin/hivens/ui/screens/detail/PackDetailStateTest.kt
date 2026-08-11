package hivens.ui.screens.detail

import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.core.data.PackReference
import hivens.core.data.SessionData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The resolve path had no coverage while it lived in the screen: a composable
 * that owns its own IO cannot be exercised without a composition, which is half
 * of why this issue exists.
 *
 * What matters here is that the screen READS the record rather than holding a
 * copy of it: everything that rewrites an instance -- the settings window, an
 * update on the app scope, the playtime a finished session writes back -- does so
 * through the same registry, and the screen has to show what it says.
 */
class PackDetailStateTest {

    private fun pack(id: String = "inst-1", name: String = "Industrial") = PackInstance(
        id = id,
        packRef = PackReference(PackOrigin.Mirror, name, "5"),
        displayName = name,
        instanceDirName = name.lowercase(),
        createdAtEpoch = 0L,
    )

    private class FakeRepo(initial: List<PackInstance> = emptyList()) : IPackRepository {
        private val instances = MutableStateFlow(initial)

        override fun observe(): Flow<List<PackInstance>> = instances
        override suspend fun list(): List<PackInstance> = instances.value
        override suspend fun get(id: String): PackInstance? = instances.value.firstOrNull { it.id == id }
        override suspend fun put(instance: PackInstance) {
            instances.update { current ->
                if (current.any { it.id == instance.id }) current.map { if (it.id == instance.id) instance else it }
                else current + instance
            }
        }
        override suspend fun delete(id: String) {
            instances.update { current -> current.filterNot { it.id == id } }
        }
    }

    private fun state(
        repo: IPackRepository,
        onLaunch: (SessionData, PackInstance) -> Unit = { _, _ -> },
        onAbort: () -> Unit = {},
        onOpenFolder: (Path) -> Unit = {},
    ) = PackDetailState(
        instanceId = "inst-1",
        repo = repo,
        dataDir = Path.of("/data"),
        launch = onLaunch,
        abort = onAbort,
        openInFileManager = onOpenFolder,
    )

    /** Starts the screen's collection on the test's own scope and lets it settle. */
    private fun TestScope.observing(state: PackDetailState): PackDetailState {
        backgroundScope.launch { state.observe() }
        runCurrent()
        return state
    }

    @Test
    fun `resolves from the registry's first emission`() = runTest {
        val state = observing(state(FakeRepo(listOf(pack()))))

        assertEquals(PackResolution.Ready(pack()), state.resolution)
    }

    @Test
    fun `a pack the registry does not have is a dead end`() = runTest {
        val state = observing(state(FakeRepo()))

        assertEquals(PackResolution.NotFound, state.resolution)
    }

    @Test
    fun `a rewrite of the record reaches the screen`() = runTest {
        // What a rename in the settings window, an update applied on the app scope
        // and the playtime written after a session all look like from here.
        val repo = FakeRepo(listOf(pack()))
        val state = observing(state(repo))

        repo.put(pack(name = "Renamed"))
        runCurrent()

        assertEquals("Renamed", state.pack?.displayName)
    }

    @Test
    fun `a deleted pack turns the screen into a dead end`() = runTest {
        val repo = FakeRepo(listOf(pack()))
        val state = observing(state(repo))

        repo.delete("inst-1")
        runCurrent()

        assertEquals(PackResolution.NotFound, state.resolution)
    }

    @Test
    fun `another instance changing says nothing about this one`() = runTest {
        val repo = FakeRepo(listOf(pack()))
        val state = observing(state(repo))

        repo.put(pack(id = "inst-2", name = "Something else"))
        runCurrent()

        assertEquals(PackResolution.Ready(pack()), state.resolution)
    }

    @Test
    fun `the instance directory follows the resolved pack`() = runTest {
        val state = state(FakeRepo(listOf(pack())))
        assertNull(state.instanceDir, "nothing is resolved yet")

        observing(state)

        assertEquals(Path.of("/data", "instances", "industrial"), state.instanceDir)
    }

    @Test
    fun `play launches the resolved pack and nothing before that`() = runTest {
        var launched: PackInstance? = null
        val state = state(FakeRepo(listOf(pack())), onLaunch = { _, p -> launched = p })
        val session = SessionData()

        state.play(session)
        assertNull(launched, "there is no pack to launch until it resolves")

        observing(state)
        state.play(session)
        assertEquals("inst-1", launched?.id)
    }

    @Test
    fun `play carries the record as it stands now`() = runTest {
        // The launch reads the runtime -- heap, java path, jvm args -- so handing it
        // the copy the screen opened with would run the game on settings the user
        // has since changed.
        var launched: PackInstance? = null
        val repo = FakeRepo(listOf(pack()))
        val state = observing(state(repo, onLaunch = { _, p -> launched = p }))

        repo.put(pack(name = "Renamed"))
        runCurrent()
        state.play(SessionData())

        assertEquals("Renamed", launched?.displayName)
    }

    @Test
    fun `opening the folder waits for a resolved pack`() = runTest {
        var opened: Path? = null
        val state = state(FakeRepo(listOf(pack())), onOpenFolder = { opened = it })

        state.openFolder()
        assertNull(opened, "there is no directory to open before the pack resolves")

        observing(state)
        state.openFolder()
        assertEquals(Path.of("/data", "instances", "industrial"), opened)
    }

    @Test
    fun `abort forwards even with nothing resolved`() = runTest {
        var aborted = false
        state(FakeRepo(), onAbort = { aborted = true }).abortLaunch()
        assertTrue(aborted, "a launch can be running for a pack this screen never resolved")
    }
}
