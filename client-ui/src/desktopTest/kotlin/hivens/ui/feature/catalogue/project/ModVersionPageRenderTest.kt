package hivens.ui.feature.catalogue.project

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hivens.catalogue.api.BuildPageView
import hivens.catalogue.api.DependencyKind
import hivens.catalogue.api.GameVersionTag
import hivens.catalogue.api.InstallOffer
import hivens.catalogue.api.ProjectPageView
import hivens.catalogue.api.ProjectTarget
import hivens.catalogue.api.VersionDependency
import hivens.core.api.dto.modrinth.ModrinthGameVersion
import hivens.core.api.dto.modrinth.ModrinthProject
import hivens.core.api.dto.modrinth.ModrinthVersion
import hivens.i18n.api.LocalTranslator
import hivens.launcher.content.ContentKind
import hivens.launcher.instance.loadersFor
import hivens.themes.Themes
import hivens.ui.feature.catalogue.toBuild
import hivens.ui.feature.catalogue.toDetails
import hivens.ui.feature.catalogue.toTags
import hivens.ui.i18n.NexiraStrings
import hivens.ui.theme.NxTheme
import kotlinx.serialization.json.Json
import org.jetbrains.skia.Data
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files
import javax.swing.SwingUtilities
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue
import hivens.ui.theme.NxColor

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

    private val gameVersions: List<GameVersionTag> by lazy {
        json.decodeFromString<List<ModrinthGameVersion>>(resource("game_versions.json")).toTags()
    }

    private val builds: List<ModrinthVersion> by lazy {
        json.decodeFromString<List<ModrinthVersion>>(resource("iris.versions.json"))
    }

    /** The project the build belongs to, read and set directly: a sheet asks nothing. */
    private fun project(): ProjectPageView {
        val p = json.decodeFromString<ModrinthProject>(resource("sodium.project.json"))
        return ProjectPageView(
            target = ProjectTarget.Catalogue(p.id, intoInstanceId = "inst-1"),
            visit = "sheet",
            loading = false,
            project = p.toDetails(),
            gameVersionTags = gameVersions,
            // A page reached from a pack's browser, which is the case the action on the
            // right exists for.
            install = InstallOffer.Install("Industrial"),
        )
    }

    /** The build with the most to say: several game versions, a changelog, dependencies. */
    private fun build(): BuildPageView {
        val chosen = builds.firstOrNull { !it.changelog.isNullOrBlank() } ?: builds.first()
        return BuildPageView(
            versionId = chosen.id,
            visit = "sheet",
            loading = false,
            build = chosen.toBuild(),
            // Named as the page names them, rather than as the wire carries them. Both
            // kinds are present on purpose: the two headings have to read as different
            // statements and not as one list split in half.
            dependencies = listOf(
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
            ),
        )
    }

    @Test
    fun `a build page draws its five sections`() = sheet("modversion-build.png", project(), build())

    /**
     * A fabric build offered to a NeoForge pack: still installable, since the reader
     * came for this one, with the line under the button saying which way they step.
     */
    @Test
    fun `a build that does not fit the pack says so under its install`() {
        val project = project().copy(
            packMcVersion = "1.21.1",
            packLoader = "neoforge",
            packLoaders = ContentKind.entries.flatMap { loadersFor(it, "neoforge", "1.21.1") },
        )
        val fabric = builds.first { "fabric" in it.loaders }
        val build = build().copy(versionId = fabric.id, build = fabric.toBuild())
        sheet("modversion-build-misfit.png", project, build)
    }

    /** A build already in the pack: the button says where, and the page still reads as the build's own. */
    @Test
    fun `a build already in the pack`() {
        val project = project().copy(install = InstallOffer.Present("Industrial"))
        sheet("modversion-build-present.png", project, build())
    }

    private fun sheet(name: String, project: ProjectPageView, build: BuildPageView) {
        val out = Path.of("build/render", name)
        Files.createDirectories(out.parent)
        var png: Data? = null
        SwingUtilities.invokeAndWait {
            val scene = ImageComposeScene(1160, 900, density = Density(1f)) {
                NxTheme(Themes.default, dark = true) {
                    CompositionLocalProvider(LocalTranslator provides NexiraStrings.translator("ru")) {
                        Box(Modifier.fillMaxSize().background(NxColor.page).padding(14.dp)) {
                            ModVersionBody(
                                project = project,
                                build = build,
                                onRetry = {},
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
            }
            png = try {
                var t = 0L
                repeat(24) { scene.render(t).close(); t += 16_000_000L }
                scene.render(t).encodeToData(EncodedImageFormat.PNG) ?: error("PNG encode failed")
            } finally {
                scene.close()
            }
        }
        val bytes = checkNotNull(png).bytes
        Files.write(out, bytes)
        assertTrue(bytes.size > 40_000, "$name drew almost nothing (${bytes.size} bytes)")
    }
}
