package hivens.ui

import hivens.core.io.IconProcessor
import hivens.ui.host.WindowPolicy
import hivens.ui.platform.AndroidIconProcessor
import hivens.ui.widgets.modules.NoWidgetModuleHost
import hivens.ui.widgets.services.MusicPlayerService
import hivens.widget.generated.GeneratedWidgetRegistry
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * What the phone adds to the shared UI bindings: its own player behind the widgets, the
 * icon downscaler, a module host that lists nothing until widget modules ship as dex, and
 * a window registry that holds the Activity and refuses a second window.
 */
fun androidUiModule(music: MusicPlayerService): Module = module {
    includes(
        uiCoreModule(
            builtIn = GeneratedWidgetRegistry,
            moduleHost = { _, _ -> NoWidgetModuleHost },
            windowPolicy = WindowPolicy.Single(ONE_WINDOW),
        ),
    )
    single<IconProcessor> { AndroidIconProcessor() }
    single<MusicPlayerService> { music }
}

/** What a request for a second window is told on a phone. */
internal const val ONE_WINDOW = "the launcher on a phone is drawn in its one Activity window, and a second window cannot open"
