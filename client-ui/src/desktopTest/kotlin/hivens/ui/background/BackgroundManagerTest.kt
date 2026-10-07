package hivens.ui.background

import hivens.ui.bootstrap.RecoveryIo
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@OptIn(ExperimentalPathApi::class)
class BackgroundManagerTest {

    private val dir = createTempDirectory("background-manager")
    private val json = Json { ignoreUnknownKeys = true }

    @AfterTest
    fun tearDown() {
        RecoveryIo.resetForTests()
        dir.deleteRecursively()
    }

    @Test
    fun `a change is live before it is written`() {
        val manager = BackgroundManager(dir, json)
        manager.update { it.copy(enabled = true, blurRadius = 12f) }

        assertEquals(BackgroundSettings(enabled = true, blurRadius = 12f), manager.settings.value)
        assertFalse(Files.exists(dir.resolve("background.json")))
    }

    @Test
    fun `flush writes the change for the next process`() {
        val manager = BackgroundManager(dir, json)
        manager.update { it.copy(enabled = true, darkenAmount = 0.7f) }
        manager.flush()

        assertEquals(manager.settings.value, BackgroundManager(dir, json).settings.value)
    }

    @Test
    fun `nothing changed is nothing written`() {
        val manager = BackgroundManager(dir, json)
        manager.flush()

        assertFalse(Files.exists(dir.resolve("background.json")))
    }

    @Test
    fun `a reset from the recovery surface is not written back over`() {
        val manager = BackgroundManager(dir, json)
        manager.update { it.copy(enabled = true) }
        manager.flush()
        manager.update { it.copy(blurRadius = 4f) }

        RecoveryIo.resetCustomization(dir)
        manager.flush()

        assertFalse(Files.exists(dir.resolve("background.json")))
    }

    // Two writers each changing their own field. The volume used to be set on a copy
    // the settings screen held, so the screen's next change put the old volume back.
    @Test
    fun `one writer's change survives another's`() {
        val manager = BackgroundManager(dir, json)
        manager.update { it.copy(audioVolume = 0.25f) }
        manager.update { it.copy(imagePath = "/w/clip.mp4", enabled = true) }

        val now = manager.settings.value
        assertEquals(0.25f, now.audioVolume)
        assertEquals("/w/clip.mp4", now.imagePath)
    }
}
