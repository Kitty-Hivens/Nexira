package hivens.ui

import androidx.compose.ui.window.application
import hivens.launcher.bootstrap.LauncherBootstrap
import hivens.ui.identity.SkinManager
import hivens.ui.notifications.IndicationCenter
import hivens.ui.notifications.NotificationCenter
import hivens.ui.notifications.SessionRegistry
import hivens.ui.notifications.drivers.PackLaunchDriver
import hivens.ui.puppet.PuppetServerLoader
import hivens.ui.utils.GameConsoleService
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

    // Notification subsystem: data-only state holders (Center +
    // Registry) are pure singletons; PackLaunchDriver bridges them
    // to LauncherController, registered as a regular single so the
    // UI's per-launch click can resolve it via koinInject.
    single { NotificationCenter() }
    single { IndicationCenter() }
    single { SessionRegistry(appScope = get()) }
    single {
        PackLaunchDriver(
            controller    = get(),
            notifications = get(),
            indications   = get(),
            sessions      = get(),
            gameConsole   = get(),
            appScope      = get(),
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
