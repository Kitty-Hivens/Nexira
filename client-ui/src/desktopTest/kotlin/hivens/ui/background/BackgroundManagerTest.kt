package hivens.ui.background

import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.io.path.ExperimentalPathApi
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
        dir.deleteRecursively()
    }

    @Test
    fun `a staged change is what load answers before it is written`() {
        val manager = BackgroundManager(dir, json)
        val staged = BackgroundSettings(enabled = true, blurRadius = 12f)
        manager.stage(staged)

        assertEquals(staged, manager.load())
        assertFalse(Files.exists(dir.resolve("background.json")))
    }

    @Test
    fun `flush writes the staged change for the next process`() {
        val manager = BackgroundManager(dir, json)
        val staged = BackgroundSettings(enabled = true, darkenAmount = 0.7f)
        manager.stage(staged)
        manager.flush()

        assertEquals(staged, BackgroundManager(dir, json).load())
    }

    @Test
    fun `a later stage wins over the one being flushed`() {
        val manager = BackgroundManager(dir, json)
        manager.stage(BackgroundSettings(opacity = 0.5f))
        manager.flush()
        val later = BackgroundSettings(opacity = 0.9f)
        manager.stage(later)

        assertEquals(later, manager.load())
        manager.flush()
        assertEquals(later, BackgroundManager(dir, json).load())
    }
}
