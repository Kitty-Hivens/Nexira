package hivens.ui

import androidx.compose.ui.window.application
import hivens.core.api.interfaces.ISettingsService
import hivens.launcher.bootstrap.LauncherBootstrap
import hivens.ui.i18n.AppLocale
import hivens.ui.i18n.stringsFor
import hivens.ui.identity.SkinManager
import hivens.ui.notifications.IndicationCenter
import hivens.ui.notifications.NotificationCenter
import hivens.ui.notifications.SessionRegistry
import hivens.ui.notifications.drivers.PackLaunchDriver
import hivens.ui.puppet.PuppetServerLoader
import hivens.config.Storage
import hivens.ui.audio.AudioPlayer
import hivens.ui.editor.EditModeController
import hivens.ui.editor.presets.PresetRepository
import hivens.ui.utils.GameConsoleService
import java.nio.file.Path
import hivens.widget.api.WidgetRegistry
import hivens.widget.generated.GeneratedWidgetRegistry
import org.jetbrains.compose.resources.ExperimentalResourceApi
import org.koin.dsl.module

// ─── DI ──────────────────────────────────────────────────────────────────────

/**
 * Compose-aware Koin singletons that the ui module owns. Passed into
 * [LauncherBootstrap.preBoot] so they land in the same Koin context as
 * the launcher's own modules without inverting the module dependency
 * direction (client-launcher must not know about client-ui types).
 */
val uiModule = module {
    single { SkinManager(get(), get()) }
    single { GameConsoleService(get()) }

    // Widget kernel registry. KSP-generated; entries land as @Widget
    // composables are added across the codebase. Kernel-1 starts the
    // registry empty -- surface refactors arrive in kernel-3.
    single<WidgetRegistry> { GeneratedWidgetRegistry }

    // Editor mutation facade. Holds no state itself; fires LayoutGraph
    // updates into the shared CoroutineScope so callers stay
    // fire-and-forget.
    single { EditModeController(repo = get(), scope = get()) }

    // Audio engine for MusicPlayerWidget. Survives recomposition;
    // playback state lives in the singleton so swapping the widget
    // out of the layout does not stop playback.
    single { AudioPlayer(scope = get()) }

    // Preset storage. One file per preset under <dataDir>/presets/.
    // Atomic write + share-by-file design lets users export to
    // anywhere.
    single {
        val dataDir: Path = get()
        PresetRepository(
            presetsDir = dataDir.resolve(Storage.PRESETS_DIR),
            json       = get(),
        )
    }

    // Notification subsystem: data-only state holders (Center +
    // Registry) are pure singletons; PackLaunchDriver bridges them
    // to LauncherController, registered as a regular single so the
    // UI's per-launch click can resolve it via koinInject.
    single { NotificationCenter() }
    single { IndicationCenter() }
    single { SessionRegistry(appScope = get()) }
    single {
        val settingsService: ISettingsService = get()
        PackLaunchDriver(
            controller      = get(),
            notifications   = get(),
            indications     = get(),
            sessions        = get(),
            gameConsole     = get(),
            appScope        = get(),
            stringsProvider = { stringsFor(AppLocale.fromTag(settingsService.getSettings().locale)) },
        )
    }
}

// ─── Entry Point ─────────────────────────────────────────────────────────────

@OptIn(ExperimentalResourceApi::class)
fun main() {
    val boot = LauncherBootstrap.preBoot(listOf(uiModule))

    // Puppet mode: opt-in localhost HTTP control surface for automated
    // UI driving (see hivens.ui.puppet.PuppetServerLifecycle + Loader).
    // Two-layer gating: build-time SPI (RealPuppetServer ships only when
    // -PauraPuppetPort=N is on the Gradle command line) + runtime system
    // property (-Dnexira.puppet.port=N must be set to actually bind).
    // MUST run after Koin (LauncherBootstrap.preBoot) so PuppetRegistry-
    // using Composables can resolve their dependencies, and before
    // `application` so the server is listening when the first Composable
    // registers itself.
    PuppetServerLoader.instance.startIfRequested()

    application { AppShell(boot) }
}
