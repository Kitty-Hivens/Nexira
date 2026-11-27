package hivens.ui.screens

import hivens.pages.api.PagesApi
import hivens.pages.api.ScreenContribution
import hivens.ui.navigation.LauncherScreens
import hivens.widget.api.CompiledModule
import hivens.widget.api.ModuleContribution
import hivens.widget.api.ModuleStrings
import hivens.widget.model.ApiVersion

/**
 * The launcher's own screens, contributed to the pages module the way a module brings one: each drawn by the widget
 * `screen.<id>`, which the launcher compiles in. Brought in whatever the document names, as what the built-in widgets
 * require is, since the rail and the home page lead to them.
 */
object LauncherScreensModule : CompiledModule {
    /** What a document or a module names it by. */
    const val ID: String = "nexira.launcher.screens"

    override val id: String = ID
    override val name: String = "launcher.screens.name"
    override val strings: ModuleStrings = ModuleStrings.resources(LauncherScreensModule::class.java, ID)
    override val version: ApiVersion = ApiVersion(1, 0)
    override val requires: Map<String, ApiVersion> = mapOf(PagesApi.ID to PagesApi.VERSION)
    override val contributions: List<ModuleContribution> =
        LauncherScreens.all.map { screen -> ScreenContribution(screen.value, screen.value, screenKind(screen.value)) }
}
