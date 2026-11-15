package hivens.packs

import hivens.core.data.CachedManifestSnapshot
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.core.data.PackReference
import hivens.core.launch.InstanceWork
import hivens.core.launch.LaunchError
import hivens.core.launch.LaunchState
import hivens.core.launch.PrepareStage
import hivens.core.update.CompatChange
import hivens.core.update.PackUpdateStatus
import hivens.core.update.UpdateDirection
import hivens.launcher.PackOperation
import hivens.launcher.PackOperationKind
import hivens.launcher.PackOperationPhase
import hivens.launcher.instance.PackInstanceService.DeleteOutcome
import hivens.packs.api.LaunchView
import hivens.packs.api.OperationStatus
import hivens.packs.api.PackOperationView
import hivens.packs.api.PackSource
import hivens.packs.api.PackUpdateView
import hivens.packs.api.PackView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/** The engine's records as the packs API carries them: plain data, words a widget can match on. */
class PackViewsTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() = scope.cancel()

    @Test
    fun `a pack reads as its plain facts, the build installed and what it runs on`() {
        val pack = PackInstance(
            id = "p1",
            packRef = PackReference(PackOrigin("Mirror"), "industrial", "5"),
            displayName = "Industrial",
            instanceDirName = "industrial-dir",
            createdAtEpoch = 100,
            lastPlayedEpochOrZero = 200,
            playtimeSeconds = 7200,
            pinnedPackVersion = "5.1",
            forkedFrom = PackReference(PackOrigin("Modrinth"), "upstream", null),
            cachedManifest = CachedManifestSnapshot("1.21.1", "neoforge", "21.1.0", 21),
            iconUrl = "icon",
            bannerUrl = "banner",
            followLatest = false,
        )
        assertEquals(
            PackView(
                id = "p1",
                name = "Industrial",
                // The source's id as the record carries it.
                source = PackSource("Mirror", "industrial", "5"),
                forkedFrom = PackSource("Modrinth", "upstream", null),
                packVersion = "5.1",
                gameVersion = "1.21.1",
                loader = "neoforge",
                loaderVersion = "21.1.0",
                iconUrl = "icon",
                bannerUrl = "banner",
                folder = "industrial-dir",
                createdAtEpochSecond = 100,
                lastPlayedEpochSecond = 200,
                playtimeSeconds = 7200,
                followLatest = false,
            ),
            pack.toPackView(),
        )
    }

    @Test
    fun `a pack from a source no module brings lists under that source's id`() {
        val pack = PackInstance("p", PackReference(PackOrigin("Curseforge"), "cozy", "1"), "Cozy", "cozy", 0)
        assertEquals(PackSource("Curseforge", "cozy", "1"), pack.toPackView().source)
    }

    @Test
    fun `a pack not prepared yet says nothing of what it runs on, rather than a blank`() {
        val pack = PackInstance("p", PackReference(PackOrigin.Local, "p", null), "P", "p", 0, cachedManifest = CachedManifestSnapshot("", "", "", 0))
        val view = pack.toPackView()
        assertEquals(null, view.gameVersion)
        assertEquals(null, view.loader)
        assertEquals(null, view.packVersion)
    }

    @Test
    fun `the launch reads as words, its handle left in the engine`() {
        assertEquals(LaunchView.Preparing("jvm", 0.5f), LaunchState.Prepare(PrepareStage.JVM, 0.5f).toView())
        assertEquals(LaunchView.Failed("exit-code", "137"), LaunchState.Error(LaunchError.ExitCode(137)).toView())
        assertEquals(LaunchView.Failed("busy", "content-update"), LaunchState.Error(LaunchError.InstanceBusy(InstanceWork.ContentUpdate)).toView())
        assertEquals(LaunchView.Failed("two-factor-expired"), LaunchState.Error(LaunchError.TwoFactorExpired).toView())
    }

    @Test
    fun `a waiting build says its direction and its grade in the API's words`() {
        assertEquals(
            PackUpdateView.Pending("6", PackUpdateView.Pending.OLDER, "loader-swap", held = false),
            PackUpdateStatus.Pending("6", UpdateDirection.Older, CompatChange.LoaderSwap).toView(),
        )
        assertEquals(PackUpdateView.Pending.NEWER, (PackUpdateStatus.Pending("6", UpdateDirection.Newer, CompatChange.Same).toView() as PackUpdateView.Pending).direction)
    }

    @Test
    fun `an operation says how it ended, and a refusal or a stale preview is not a success`() {
        fun op(phase: PackOperationPhase) = PackOperation("p", PackOperationKind.Reattach, phase).toView()
        assertEquals(PackOperationView("reattach", OperationStatus.Done("7")), op(PackOperationPhase.Updated("7")))
        assertEquals(PackOperationView("reattach", OperationStatus.Failed("preview-stale")), op(PackOperationPhase.PreviewStale))
        assertEquals(PackOperationView("reattach", OperationStatus.Failed("busy", "update")), op(PackOperationPhase.Refused(InstanceWork.Update)))
        assertEquals(OperationStatus.Failed("incomplete", "a.jar"), op(PackOperationPhase.Repaired(3, 2, listOf("a.jar"))).status)
        assertEquals(OperationStatus.Failed("game-running"), DeleteOutcome.GameRunning.toStatus())
    }

    @Test
    fun `the last operation per pack is whichever changed last, a delete or the service's`() = runBlocking {
        val operations = MutableStateFlow(mapOf("p" to PackOperation("p", PackOperationKind.Update, PackOperationPhase.Updated("2"))))
        val outcomes = PackOutcomes(operations, scope)
        assertEquals(PackOperationView("update", OperationStatus.Done("2")), outcomes.all.value["p"])

        outcomes.deleting("p")
        outcomes.deleted("p", DeleteOutcome.Busy(InstanceWork.Repair))
        assertEquals(PackOperationView("delete", OperationStatus.Failed("busy", "repair")), outcomes.all.value["p"])

        operations.value = mapOf("p" to PackOperation("p", PackOperationKind.Repair, PackOperationPhase.Running(1, 4, "x")))
        withTimeout(5.seconds) { outcomes.all.first { it["p"]?.kind == "repair" } }
        assertEquals(PackOperationView("repair", OperationStatus.Running(1, 4)), outcomes.all.value["p"])

        // The service dropping its entry takes away what it showed, and leaves a delete written since.
        operations.value = mapOf(
            "p" to PackOperation("p", PackOperationKind.Repair, PackOperationPhase.Repaired(4, 4)),
            "r" to PackOperation("r", PackOperationKind.Update, PackOperationPhase.Updated("3")),
        )
        withTimeout(5.seconds) { outcomes.all.first { "r" in it } }
        outcomes.deleted("p", DeleteOutcome.Deleted)
        operations.value = emptyMap()
        withTimeout(5.seconds) { outcomes.all.first { "r" !in it } }
        assertEquals(PackOperationView("delete", OperationStatus.Done()), outcomes.all.value["p"])
    }
}
