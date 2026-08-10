package hivens.ui.screens.detail

import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.core.data.PackReference
import hivens.core.data.SessionData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
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
 * What matters here is the difference between "this pack is gone" and "the read
 * came back empty for a moment" -- the screen renders a dead end for the first
 * and must not for the second.
 */
class PackDetailStateTest {

    private fun pack(id: String = "inst-1", name: String = "Industrial") = PackInstance(
        id = id,
        packRef = PackReference(PackOrigin.Mirror, name, "5"),
        displayName = name,
        instanceDirName = name.lowercase(),
        createdAtEpoch = 0L,
    )

    private class FakeRepo(
        private val observed: List<PackInstance> = emptyList(),
        private val stored: MutableMap<String, PackInstance> = mutableMapOf(),
    ) : IPackRepository {
        var getCalls = 0
            private set

        override fun observe(): Flow<List<PackInstance>> = flowOf(observed)
        override suspend fun list(): List<PackInstance> = observed
        override suspend fun get(id: String): PackInstance? {
            getCalls++
            return stored[id]
        }
        override suspend fun put(instance: PackInstance) { stored[instance.id] = instance }
        override suspend fun delete(id: String) { stored.remove(id) }
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

    @Test
    fun `resolves from the observed list without a direct read`() = runTest {
        val repo = FakeRepo(observed = listOf(pack()))
        val state = state(repo)

        state.resolve()

        assertEquals(PackResolution.Ready(pack()), state.resolution)
        assertEquals(0, repo.getCalls, "the list already had it; a second read is a wasted round trip")
    }

    @Test
    fun `falls back to a direct read when the list has not emitted it`() = runTest {
        // Navigation can land here before the repository's first emission, so a
        // miss on the flow is not a missing pack.
        val repo = FakeRepo(observed = emptyList(), stored = mutableMapOf("inst-1" to pack()))
        val state = state(repo)

        state.resolve()

        assertEquals(PackResolution.Ready(pack()), state.resolution)
        assertEquals(1, repo.getCalls)
    }

    @Test
    fun `a pack in neither place is not found`() = runTest {
        val state = state(FakeRepo())

        state.resolve()

        assertEquals(PackResolution.NotFound, state.resolution)
    }

    @Test
    fun `refresh picks up a record an operation rewrote`() = runTest {
        val repo = FakeRepo(observed = listOf(pack()), stored = mutableMapOf("inst-1" to pack(name = "Renamed")))
        val state = state(repo)
        state.resolve()

        state.refresh()

        assertEquals("Renamed", state.pack?.displayName)
    }

    @Test
    fun `an empty refresh keeps what is on screen`() = runTest {
        val repo = FakeRepo(observed = listOf(pack()))
        val state = state(repo)
        state.resolve()

        state.refresh()

        assertEquals(
            PackResolution.Ready(pack()),
            state.resolution,
            "a transient miss must not turn a live screen into a dead end",
        )
    }

    @Test
    fun `the instance directory follows the resolved pack`() = runTest {
        val state = state(FakeRepo(observed = listOf(pack())))
        assertNull(state.instanceDir, "nothing is resolved yet")

        state.resolve()

        assertEquals(Path.of("/data", "instances", "industrial"), state.instanceDir)
    }

    @Test
    fun `play launches the resolved pack and nothing before that`() = runTest {
        var launched: PackInstance? = null
        val state = state(FakeRepo(observed = listOf(pack())), onLaunch = { _, p -> launched = p })
        val session = SessionData()

        state.play(session)
        assertNull(launched, "there is no pack to launch until it resolves")

        state.resolve()
        state.play(session)
        assertEquals("inst-1", launched?.id)
    }

    @Test
    fun `adopt takes the settings window's rewrite without a read`() = runTest {
        val repo = FakeRepo(observed = listOf(pack()))
        val state = state(repo)
        state.resolve()

        state.adopt(pack(name = "Edited"))

        assertEquals("Edited", state.pack?.displayName)
        assertEquals(0, repo.getCalls, "the caller handed us the new record; re-reading it is round-tripping our own write")
    }

    @Test
    fun `opening the folder waits for a resolved pack`() = runTest {
        var opened: Path? = null
        val state = state(FakeRepo(observed = listOf(pack())), onOpenFolder = { opened = it })

        state.openFolder()
        assertNull(opened, "there is no directory to open before the pack resolves")

        state.resolve()
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
