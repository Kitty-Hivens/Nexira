package hivens.ui.screens.mod

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hivens.core.api.HttpClientProvider
import hivens.core.api.dto.modrinth.ModrinthGameVersion
import hivens.core.api.dto.modrinth.ModrinthProject
import hivens.core.api.dto.modrinth.ModrinthVersion
import hivens.core.data.PackInstance
import hivens.core.api.interfaces.IPackRepository
import hivens.core.net.TransferEngine
import hivens.launcher.instance.InstanceContentScanner
import hivens.launcher.modrinth.ModrinthClient
import hivens.ui.i18n.LocalStrings
import hivens.ui.i18n.RussianStrings
import hivens.ui.theme.NxTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * One build's page, from the fixtures the project page's sheet already uses.
 *
 * The page is five stacked sections whose lengths are all decided by the data:
 * a build can ship one file or four, name two dependencies or none, and carry a
 * changelog of one line or forty. What a sheet catches is the shape those make
 * together -- headings at the same rank as the rail's, the dot-separated header
 * line holding on one row, a section that is absent rather than empty.
 */
class ModVersionPageRenderTest {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun resource(name: String): String =
        checkNotNull(javaClass.classLoader.getResourceAsStream("catalogue/$name")) { "missing fixture $name" }
            .bufferedReader().use { it.readText() }

    private val gameVersions: List<ModrinthGameVersion> by lazy {
        json.decodeFromString<List<ModrinthGameVersion>>(resource("game_versions.json"))
    }

    /** A client stood up and never reached: the sheet sets every answer directly. */
    private fun offlineClient(): ModrinthClient {
        val http = HttpClientProvider { error("a render sheet makes no requests") }
        return ModrinthClient(http, TransferEngine(http))
    }

    private object EmptyRepo : IPackRepository {
        private val flow = MutableStateFlow(emptyList<PackInstance>())
        override fun observe(): StateFlow<List<PackInstance>> = flow
        override suspend fun list(): List<PackInstance> = emptyList()
        override suspend fun get(id: String): PackInstance? = null
        override suspend fun put(instance: PackInstance) {}
        override suspend fun delete(id: String) {}
    }

    private fun project(): ModDetailState {
        val p = json.decodeFromString<ModrinthProject>(resource("sodium.project.json"))
        val s = ModDetailState(
            target = ModTarget.Catalogue(p.id, intoInstanceId = "inst-1"),
            modrinth = offlineClient(),
            repo = EmptyRepo,
            dataDir = Path.of("."),
            scanner = InstanceContentScanner(),
            open = OpenProjectState(),
            strings = RussianStrings,
        )
        s.project = p
        s.loading = false
        s.gameVersionTags = gameVersions
        // A page reached from a pack's browser, which is the case the action on the
        // right exists for.
        s.install = InstallAction.Install("Industrial")
        return s
    }

    /** The build with the most to say: several game versions, a changelog, dependencies. */
    private fun build(): ModVersionState {
        val versions = json.decodeFromString<List<ModrinthVersion>>(resource("iris.versions.json"))
        val chosen = versions.firstOrNull { !it.changelog.isNullOrBlank() } ?: versions.first()
        val state = ModVersionState(offlineClient())
        state.version = chosen
        state.loading = false
        // Named as the page names them, rather than as the wire carries them. Both
        // kinds are present on purpose: the two headings have to read as different
        // statements and not as one list split in half.
        state.dependencies = listOf(
            // One with the catalogue's art and a pinned build, one with neither, and
            // one the author warns against: the three shapes a row can take.
            VersionDependency(
                projectId = "AANobbMI",
                title = "Sodium",
                iconUrl = null,
                versionNumber = "0.6.13",
                kind = DependencyKind.Required,
            ),
            VersionDependency(
                projectId = "P7dR8mSH",
                title = "Fabric API",
                iconUrl = null,
                versionNumber = null,
                kind = DependencyKind.Required,
            ),
            VersionDependency(
                projectId = "H8CaAYZC",
                title = "Starlight",
                iconUrl = null,
                versionNumber = null,
                kind = DependencyKind.Incompatible,
            ),
        )
        return state
    }

    @Test
    fun `a build page draws its five sections`() {
        val out = Path.of("build/render", "modversion-build.png")
        Files.createDirectories(out.parent)
        val scene = ImageComposeScene(1160, 900, density = Density(1f)) {
            NxTheme(useDarkTheme = true) {
                CompositionLocalProvider(LocalStrings provides RussianStrings) {
                    Box(Modifier.fillMaxSize().background(NxTheme.colors.background).padding(14.dp)) {
                        ModVersionBody(
                            project = project(),
                            build = build(),
                            onRetry = {},
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
        val png = try {
            var t = 0L
            repeat(24) { scene.render(t); t += 16_000_000L }
            scene.render(t).encodeToData(EncodedImageFormat.PNG) ?: error("PNG encode failed")
        } finally {
            scene.close()
        }
        Files.write(out, png.bytes)
        assertTrue(png.bytes.size > 40_000, "the page drew almost nothing (${png.bytes.size} bytes)")
    }
}
