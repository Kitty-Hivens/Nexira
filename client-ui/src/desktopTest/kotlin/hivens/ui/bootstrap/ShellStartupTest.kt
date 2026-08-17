package hivens.ui.bootstrap

import hivens.core.api.model.ServerProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The shell's bring-up sequence, which had never been run outside a live
 * launcher: it sat in a hundred-line effect wired straight to the tray library
 * and the notifier object.
 *
 * The order is the part worth pinning. The window's close path asks the tray
 * whether hiding is possible, so the tray comes up before anything can close;
 * a tray that failed has to put the window back, or a user who closed to tray
 * during init is left with a running process and no way to reach it.
 */
class ShellStartupTest {

    private fun server(name: String) = ServerProfile(name = name, assetDir = name.lowercase())

    private val allOn = StartupPolicy(
        trayEnabled = true,
        notifierEnabled = true,
        autoSyncAllPacks = true,
        autoUpdatePacks = true,
    )

    private class Recorder {
        val calls = mutableListOf<String>()
        var syncedWith: List<ServerProfile>? = null
    }

    private fun TestScope.startup(
        recorder: Recorder,
        policy: StartupPolicy = allOn,
        traySupported: Boolean = true,
        iconFails: Set<String> = emptySet(),
        cached: List<ServerProfile> = emptyList(),
        fetch: suspend () -> List<ServerProfile> = { emptyList() },
    ) = ShellStartup(
        policy = policy,
        bringUpTray = { recorder.calls += "tray" },
        bringUpNotifier = { recorder.calls += "notifier" },
        readIcon = { path ->
            if (path in iconFails) throw IOException("missing $path")
            recorder.calls += "icon:$path"
            ByteArray(1)
        },
        trayIsSupported = { traySupported },
        showWindow = { recorder.calls += "showWindow" },
        cachedRoster = { recorder.calls += "cachedRoster"; cached },
        fetchRoster = { recorder.calls += "fetchRoster"; fetch() },
        syncAll = { servers -> recorder.calls += "syncAll"; recorder.syncedWith = servers },
        recoverInterrupted = { recorder.calls += "recover" },
        autoUpdatePacks = { recorder.calls += "autoUpdate" },
        appScope = this,
    )

    @Test
    fun `the tray comes up before the roster is touched`() = runTest {
        val rec = Recorder()
        startup(rec, fetch = { listOf(server("Industrial")) }).run(windowVisible = { true })
        advanceUntilIdle()

        val tray = rec.calls.indexOf("tray")
        val fetched = rec.calls.indexOf("fetchRoster")
        assertTrue(tray >= 0, "the tray is brought up")
        assertTrue(fetched > tray, "the close path asks the tray whether hiding is possible, so it cannot wait on a network call")
    }

    @Test
    fun `a tray that did not come up puts the window back`() = runTest {
        val rec = Recorder()
        startup(rec, traySupported = false).run(windowVisible = { false })
        advanceUntilIdle()

        assertTrue("showWindow" in rec.calls, "otherwise the process runs on with no reachable UI")
    }

    @Test
    fun `a visible window is left alone`() = runTest {
        val rec = Recorder()
        startup(rec, traySupported = false).run(windowVisible = { true })
        advanceUntilIdle()

        assertFalse("showWindow" in rec.calls)
    }

    @Test
    fun `the fallback icon is tried when the first cannot be read`() = runTest {
        val rec = Recorder()
        startup(rec, iconFails = setOf("drawable/favicon.png")).run(windowVisible = { true })
        advanceUntilIdle()

        assertTrue("icon:drawable/icon.png" in rec.calls)
        assertTrue("tray" in rec.calls, "a packaging slip on one asset must not cost the session its tray")
    }

    @Test
    fun `no icon at all leaves the launcher running`() = runTest {
        val rec = Recorder()
        // A tray whose init never ran reports unsupported, which is what puts the
        // window back -- the scenario is only itself with both halves.
        startup(rec, traySupported = false, iconFails = setOf("drawable/favicon.png", "drawable/icon.png"))
            .run(windowVisible = { false })
        advanceUntilIdle()

        assertFalse("tray" in rec.calls)
        assertTrue("showWindow" in rec.calls, "otherwise a close-to-tray during init strands the process")
        assertTrue("recover" in rec.calls, "no tray is a degraded launcher, not a dead one")
    }

    @Test
    fun `each module is gated on its own flag`() = runTest {
        // Separately, so a notifier wired to the tray's flag cannot pass.
        val trayOnly = Recorder()
        startup(trayOnly, policy = allOn.copy(notifierEnabled = false)).run(windowVisible = { true })
        advanceUntilIdle()
        assertTrue("tray" in trayOnly.calls)
        assertFalse("notifier" in trayOnly.calls)

        val notifierOnly = Recorder()
        startup(notifierOnly, policy = allOn.copy(trayEnabled = false)).run(windowVisible = { true })
        advanceUntilIdle()
        assertFalse("tray" in notifierOnly.calls)
        assertTrue("notifier" in notifierOnly.calls)
    }

    @Test
    fun `interrupted updates are recovered whatever the opt-ins say`() = runTest {
        val rec = Recorder()
        val off = allOn.copy(autoSyncAllPacks = false, autoUpdatePacks = false)
        startup(rec, policy = off).run(windowVisible = { true })
        advanceUntilIdle()

        assertTrue("recover" in rec.calls, "a half-applied instance must be repaired before anything touches it")
        assertFalse("syncAll" in rec.calls)
        assertFalse("autoUpdate" in rec.calls)
    }

    @Test
    fun `the two auto passes are opted into separately`() = runTest {
        // Mirror builds and SmartyCraft clients are updated by different services
        // and have different reliability records, so neither opt-in may gate the
        // other -- a shared master is what this replaces.
        val syncOnly = Recorder()
        startup(syncOnly, policy = allOn.copy(autoUpdatePacks = false), fetch = { listOf(server("Industrial")) })
            .run(windowVisible = { true })
        advanceUntilIdle()
        assertTrue("syncAll" in syncOnly.calls)
        assertFalse("autoUpdate" in syncOnly.calls)

        val updateOnly = Recorder()
        startup(updateOnly, policy = allOn.copy(autoSyncAllPacks = false), fetch = { listOf(server("Industrial")) })
            .run(windowVisible = { true })
        advanceUntilIdle()
        assertFalse("syncAll" in updateOnly.calls)
        assertTrue("autoUpdate" in updateOnly.calls)
    }

    @Test
    fun `auto-sync does not run on an empty roster`() = runTest {
        val rec = Recorder()
        startup(rec, fetch = { emptyList() }).run(windowVisible = { true })
        advanceUntilIdle()

        assertFalse("syncAll" in rec.calls, "there is nothing to sync against")
    }

    @Test
    fun `an outage syncs against the cached roster`() = runTest {
        val rec = Recorder()
        startup(rec, cached = listOf(server("Industrial")), fetch = { emptyList() })
            .run(windowVisible = { true })
        advanceUntilIdle()

        assertEquals(listOf("Industrial"), rec.syncedWith?.map { it.name })
    }

    @Test
    fun `a cancelled fetch propagates instead of reading as an outage`() = runTest {
        val rec = Recorder()
        val startup = startup(rec, cached = listOf(server("Industrial")), fetch = { throw CancellationException("left") })

        assertFailsWith<CancellationException> { startup.run(windowVisible = { true }) }
        assertFalse("syncAll" in rec.calls, "treating a composition leave as an outage would defeat structured concurrency")
    }

    @Test
    fun `a cancelled icon read propagates too`() = runTest {
        val rec = Recorder()
        val startup = ShellStartup(
            policy = allOn,
            bringUpTray = { rec.calls += "tray" },
            bringUpNotifier = { rec.calls += "notifier" },
            readIcon = { throw CancellationException("left") },
            trayIsSupported = { true },
            showWindow = { rec.calls += "showWindow" },
            cachedRoster = { emptyList() },
            fetchRoster = { emptyList() },
            syncAll = { },
            recoverInterrupted = { rec.calls += "recover" },
            autoUpdatePacks = { },
            appScope = this,
        )

        assertFailsWith<CancellationException> { startup.run(windowVisible = { true }) }
        assertFalse("recover" in rec.calls, "a cancelled bring-up must not go on to start background work")
    }

    @Test
    fun `a fetch that throws falls back to the cache`() = runTest {
        val rec = Recorder()
        startup(rec, cached = listOf(server("Industrial")), fetch = { throw IOException("offline") })
            .run(windowVisible = { true })
        advanceUntilIdle()

        assertEquals(listOf("Industrial"), rec.syncedWith?.map { it.name })
    }
}
