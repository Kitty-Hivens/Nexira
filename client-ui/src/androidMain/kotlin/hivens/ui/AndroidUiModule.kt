package hivens.ui

import hivens.core.io.IconProcessor
import hivens.ui.platform.AndroidIconProcessor
import hivens.ui.widgets.modules.NoWidgetModuleHost
import hivens.ui.widgets.services.MusicPlayerService
import hivens.widget.generated.GeneratedWidgetRegistry
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * What the phone adds to the shared UI bindings: its own player behind the widgets, the
 * icon downscaler, and a module host that lists nothing until widget modules ship as dex.
 */
fun androidUiModule(music: MusicPlayerService): Module = module {
    includes(uiCoreModule(builtIn = GeneratedWidgetRegistry, moduleHost = { _, _ -> NoWidgetModuleHost }))
    single<IconProcessor> { AndroidIconProcessor() }
    single<MusicPlayerService> { music }
}
