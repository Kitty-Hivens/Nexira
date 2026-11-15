package hivens.ui.host

import hivens.catalogue.api.CatalogueKeys
import hivens.catalogue.api.SourceAbility
import hivens.core.api.interfaces.IPackRepository
import hivens.core.data.PackOrigin
import hivens.launcher.CrashReporter
import hivens.launcher.bootstrap.LauncherBootstrap
import hivens.launcher.platform.PlatformPaths
import hivens.launcher.sources.EngineSources
import hivens.launcher.sources.SourceOperation
import hivens.launcher.sources.SourceStatus
import hivens.launcher.sources.SourceWorks
import hivens.launcher.sources.operations
import hivens.ui.uiCoreModule
import hivens.ui.widgets.modules.NoWidgetModuleHost
import hivens.widget.api.WidgetScope
import hivens.widget.generated.GeneratedWidgetRegistry
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import org.koin.core.Koin
import org.koin.core.KoinApplication
import org.koin.dsl.module

/**
 * The sources as the launcher's own graph hands them to the engine: the launcher's modules
 * and the interface's, assembled through the bootstrap's last step with the paths in a
 * temporary directory and the standard document. Once the app level is made, every compiled
 * source the document names is loaded for the engine with the work its module brings, which
 * is what the startup auto-update and every pack screen read.
 */
class SourceWorksGraphTest {
    private lateinit var dir: Path
    private lateinit var application: KoinApplication
    private val koin: Koin get() = application.koin

    @BeforeTest
    fun start() {
        dir = Files.createTempDirectory("nexira-sources-graph")
        val paths = PlatformPaths(
            osName = "Linux",
            home = dir,
            bootstrapDataDir = { null },
            env = { if (it == "NEXIRA_DATA_DIR") dir.toString() else null },
        )
        application = LauncherBootstrap.finishBoot(
            LauncherBootstrap.Core(paths, CrashReporter(paths)),
            listOf(
                module {
                    // The real registrations resolve the per-user paths of the machine the
                    // test runs on, and these replace them.
                    single<PlatformPaths> { paths }
                    single<Path> { paths.dataDir }
                },
                uiCoreModule(builtIn = GeneratedWidgetRegistry, moduleHost = { _, _ -> NoWidgetModuleHost }),
            ),
        ).koin
    }

    @AfterTest
    fun stop() {
        val _ = runCatching { koin.get<IPackRepository>().let { (it as? AutoCloseable)?.close() } }
        application.close()
        dir.toFile().deleteRecursively()
    }

    @Test
    fun `once the app level is made the engine finds the compiled sources loaded with their work`() {
        val app = koin.get<WidgetScope>()
        val works = koin.get<SourceWorks>()

        val mirror = assertIs<SourceStatus.Loaded>(works.status(EngineSources.Mirror)).work
        assertEquals(setOf(SourceOperation.Install, SourceOperation.Update, SourceOperation.Reattach, SourceOperation.Repair), mirror.operations)
        val modrinth = assertIs<SourceStatus.Loaded>(works.status(EngineSources.Modrinth)).work
        assertEquals(setOf(SourceOperation.Install, SourceOperation.Update, SourceOperation.Reattach), modrinth.operations)
        assertEquals(SourceStatus.Missing(PackOrigin("Curseforge")), works.status(PackOrigin("Curseforge")))

        val listed = assertNotNull(app.find(CatalogueKeys.Sources)).state.value.associate { it.id to it.abilities }
        assertEquals(setOf(SourceAbility.Install, SourceAbility.Update, SourceAbility.Reattach, SourceAbility.Repair), listed[EngineSources.Mirror.id])
        assertEquals(setOf(SourceAbility.Install, SourceAbility.Update, SourceAbility.Reattach), listed[EngineSources.Modrinth.id])
    }
}
