package hivens.ui.screens.library.content

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import hivens.core.api.dto.modrinth.ModrinthFile
import hivens.core.api.dto.modrinth.ModrinthHashes
import hivens.core.api.dto.modrinth.ModrinthVersion
import hivens.launcher.instance.ContentKind
import hivens.launcher.instance.ContentRef
import hivens.launcher.instance.InstalledContent
import hivens.launcher.instance.InstanceContentUpdater
import hivens.launcher.instance.ModUpdate
import hivens.ui.theme.NxTheme
import org.jetbrains.skia.EncodedImageFormat
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Draws the three surfaces the update feature added, so they can be LOOKED at
 * rather than reasoned about from the source: the tab's toolbar in each of its
 * update states, a content row carrying the update chip and the version button,
 * and the version window itself.
 *
 * Every one of them was written without the launcher running, and a compiler
 * cannot see a control that sits a hair off its neighbours or a label that reads
 * as the wrong action. The PNGs under `build/render` are the output; the
 * assertions here only catch a sheet that failed to draw at all.
 */
class ContentUpdateSurfacesRenderTest {

    // -- fixtures ---------------------------------------------------------------

    private fun content(
        name: String,
        file: String,
        version: String?,
        enabled: Boolean = true,
    ) = InstalledContent(
        kind        = ContentKind.Mod,
        fileName    = file,
        displayName = name,
        version     = version,
        description = null,
        enabled     = enabled,
        iconBytes   = null,
        sizeBytes   = 4_200_000,
    )

    private fun rules(enabled: Boolean = true) = ContentRowRules(
        effectiveEnabled = enabled,
        showToggle       = true,
        optional         = false,
        canDelete        = true,
    )

    private fun update(file: String, to: String) = ModUpdate(
        ref              = ContentRef(ContentKind.Mod, file),
        installedVersion = "1.8.12",
        projectId        = "YL57xq9U",
        versionId        = "abc",
        versionNumber    = to,
        versionType      = "release",
        fileName         = file,
        url              = "https://example.invalid/$file",
        sha1             = "0".repeat(40),
        sizeBytes        = 4_200_000,
    )

    private fun version(
        id: String,
        number: String,
        type: String,
        published: String,
        notes: String? = null,
    ) = ModrinthVersion(
        id            = id,
        projectId     = "YL57xq9U",
        name          = number,
        versionNumber = number,
        versionType   = type,
        gameVersions  = listOf("1.21.1"),
        loaders       = listOf("neoforge"),
        datePublished = published,
        changelog     = notes,
        files         = listOf(
            ModrinthFile(
                hashes   = ModrinthHashes(sha1 = id.padEnd(40, '0')),
                url      = "https://example.invalid/$number.jar",
                filename = "iris-neoforge-$number.jar",
                primary  = true,
                size     = 3_100_000,
            ),
        ),
    )

    /** Eight builds: no search field, so the list geometry is predictable. */
    private val irisVersions = listOf(
        version("v8", "1.9.1", "release", "2026-09-02T18:10:00Z", "### 1.9.1\n\nИсправлен вылет при загрузке шейдера с `colortex7`.\n\n- Совместимость с Sodium 0.8.x\n- Мелкие правки рендера теней"),
        version("v7", "1.9.0", "release", "2026-08-21T11:00:00Z"),
        version("v6", "1.8.14-beta.1", "beta", "2026-07-30T09:25:00Z", "Бета: правка для NeoForge 21.1."),
        version("v5", "1.8.12", "release", "2026-06-14T20:00:00Z", "Стабильный билд под 1.21.1."),
        version("v4", "1.8.11", "release", "2026-05-30T08:00:00Z"),
        version("v3", "1.8.8", "release", "2026-04-02T12:00:00Z"),
        version("v2", "1.8.1", "release", "2026-02-11T16:30:00Z"),
        version("v1", "1.8.0-alpha.3", "alpha", "2026-01-19T22:45:00Z"),
    )

    // -- sheets -----------------------------------------------------------------

    @Test
    fun `toolbar states and a row with an update`() {
        val image = render(1800, 1560, Density(2f), "content-toolbar-and-row.png") {
            Sheet {
                Caption("тулбар: найдено 7 обновлений")
                Toolbar(updateCount = 7)

                Caption("тулбар: батч в полёте")
                Toolbar(
                    updateCount = 7,
                    run = InstanceContentUpdater.Run(
                        title    = "RPG-Revanced",
                        total    = 52,
                        done     = 17,
                        current  = "sodium-neoforge-0.6.13.jar",
                        failed   = emptyList(),
                        finished = false,
                    ),
                )

                Caption("тулбар: проверка не прошла")
                Toolbar(updateCount = 0, checkFailed = true)

                Caption("тулбар: обновлений нет (контрол не рисуется)")
                Toolbar(updateCount = 0)

                Caption("строки списка")
                Row(content("Iris Shaders", "iris.jar", "1.8.12"), update = update("iris.jar", "1.9.1"))
                Row(content("Sodium", "sodium.jar", "0.6.13"))
                Row(content("Distant Horizons", "dh.jar", "2.3.2-b", enabled = false), enabled = false)
            }
        }
        assertDrawn(image)
    }

    @Test
    fun `version window on the installed build`() {
        val image = render(1500, 940, Density(1f), "content-versions-installed.png") {
            VersionsWindow()
        }
        assertDrawn(image)
    }

    /**
     * The same window with the newest build picked, which is the state the action
     * label actually has to carry: on the installed row it reads "current" and
     * does nothing, one row up it has to say which way it moves the instance.
     */
    @Test
    fun `version window on a newer build`() {
        val image = render(1500, 940, Density(1f), "content-versions-newer.png", clickAt = Offset(160f, 115f)) {
            VersionsWindow()
        }
        assertDrawn(image)
    }

    // -- sheet pieces -----------------------------------------------------------

    @Composable
    private fun Sheet(body: @Composable () -> Unit) {
        Box(Modifier.fillMaxSize().background(NxTheme.colors.background)) {
            Column(
                modifier            = Modifier.fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) { body() }
        }
    }

    @Composable
    private fun Caption(text: String) {
        Text(
            text     = text,
            style    = MaterialTheme.typography.labelSmall,
            color    = NxTheme.colors.textSecondary,
            modifier = Modifier.padding(top = 8.dp),
        )
    }

    @Composable
    private fun Toolbar(updateCount: Int, checkFailed: Boolean = false, run: InstanceContentUpdater.Run? = null) {
        Toolbar(
            query           = "",
            onQuery         = {},
            filter          = ContentFilter.All,
            onFilter        = {},
            filters         = ContentFilters(),
            onFilters       = {},
            offersOptional  = false,
            offersOwner     = false,
            shownCount      = 97,
            scannedCount    = 97,
            canAdd          = true,
            canFindProjects = true,
            onAddFiles      = {},
            onFindProjects  = {},
            updateCount     = updateCount,
            offersUpdates   = true,
            checkFailed     = checkFailed,
            run             = run,
            onUpdateAll     = {},
            onCheck         = {},
        )
    }

    @Composable
    private fun Row(item: InstalledContent, update: ModUpdate? = null, enabled: Boolean = true) {
        ContentRow(
            content        = item,
            iconState      = ContentIconState.None,
            rules          = rules(enabled),
            selected       = false,
            onToggle       = {},
            onDelete       = {},
            onDetails      = {},
            resolveProject = { null },
            update         = update,
            onUpdate       = {},
            onVersions     = {},
        )
    }

    @Composable
    private fun VersionsWindow() {
        Box(Modifier.fillMaxSize().background(NxTheme.colors.background)) {
            ModVersionsWindow(
                content       = content("Iris Shaders", "iris-neoforge-1.8.12.jar", "1.8.12"),
                versions      = irisVersions,
                failed        = false,
                unknown       = false,
                installedId   = "v5",
                icon          = null,
                busyVersionId = null,
                mcVersion     = "1.21.1",
                loader        = "neoforge",
                onPick        = {},
                onDismiss     = {},
            )
        }
    }

    // -- scene ------------------------------------------------------------------

    private fun render(
        width: Int,
        height: Int,
        density: Density,
        name: String,
        clickAt: Offset? = null,
        content: @Composable () -> Unit,
    ): BufferedImage {
        val out = Path.of("build/render", name)
        Files.createDirectories(out.parent)
        val scene = ImageComposeScene(width, height, density = density) {
            NxTheme(useDarkTheme = true) { content() }
        }
        val png = try {
            var frameNanos = 0L
            repeat(12) {
                scene.render(frameNanos).close()
                frameNanos += 16_000_000L
            }
            if (clickAt != null) {
                scene.sendPointerEvent(PointerEventType.Press, clickAt)
                scene.sendPointerEvent(PointerEventType.Release, clickAt)
                repeat(12) {
                    scene.render(frameNanos).close()
                    frameNanos += 16_000_000L
                }
            }
            scene.render(frameNanos).encodeToData(EncodedImageFormat.PNG) ?: error("PNG encode failed")
        } finally {
            scene.close()
        }
        Files.write(out, png.bytes)
        return ImageIO.read(ByteArrayInputStream(png.bytes))
    }

    /**
     * The sheet drew something. Not a look at the design -- that is what the PNG
     * is for -- only a guard against a surface that collapsed to its background
     * and would otherwise be reviewed as an empty rectangle.
     */
    private fun assertDrawn(image: BufferedImage) {
        val base = image.getRGB(2, 2)
        var different = 0
        for (y in 0 until image.height step 4) {
            for (x in 0 until image.width step 4) {
                if (image.getRGB(x, y) != base) different++
            }
        }
        val sampled = (image.height / 4 + 1) * (image.width / 4 + 1)
        assertTrue(different > sampled / 50, "the sheet is all but blank: $different of $sampled samples differ")
    }
}
