package hivens.ui.screens.detail.settings

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.PackInstance
import hivens.core.data.PackOrigin
import hivens.core.data.PackReference
import hivens.ui.theme.NxTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import org.jetbrains.skia.EncodedImageFormat
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Off-screen render smoke of the floating pack-settings window at FHD and 2K.
 * ImageComposeScene rasterises the composition with no display -- fully isolated
 * from any live session -- so it both guards the window from a compose-time crash
 * and dumps a PNG under build/ for a manual look. Only the default (General)
 * section composes here, which needs just IPackRepository.
 */
class PackSettingsWindowRenderTest {

    @AfterTest fun tearDown() = stopKoin()

    private class FakeRepo : IPackRepository {
        private val flow = MutableStateFlow<List<PackInstance>>(emptyList())
        override fun observe(): Flow<List<PackInstance>> = flow
        override suspend fun list(): List<PackInstance> = emptyList()
        override suspend fun get(id: String): PackInstance? = null
        override suspend fun put(instance: PackInstance) {}
        override suspend fun delete(id: String) {}
    }

    private val pack = PackInstance(
        id = "1",
        packRef = PackReference(PackOrigin.Mirror, "industrial", "5"),
        displayName = "Индустриальная",
        instanceDirName = "industrial",
        createdAtEpoch = 0L,
        notes = "тестовая заметка",
    )

    private fun render(width: Int, height: Int, name: String) {
        startKoin { modules(module { single<IPackRepository> { FakeRepo() } }) }
        val out = Path.of("build/render", name)
        Files.createDirectories(out.parent)
        val scene = ImageComposeScene(width, height, density = Density(1f)) {
            NxTheme(useDarkTheme = true) {
                PackSettingsWindow(pack = pack, instanceDir = Path.of("/tmp/render"), onInstanceChange = {}, onDismiss = {})
            }
        }
        try {
            val png = scene.render().encodeToData(EncodedImageFormat.PNG) ?: error("PNG encode failed")
            Files.write(out, png.bytes)
        } finally {
            scene.close()
        }
        assertTrue(Files.size(out) > 0, "rendered PNG is non-empty")
    }

    @Test fun `renders at FHD 1920x1080`() = render(1920, 1080, "pack-settings-fhd.png")

    @Test fun `renders at 2K 2560x1440`() = render(2560, 1440, "pack-settings-2k.png")
}
