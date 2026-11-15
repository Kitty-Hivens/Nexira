package hivens.ui.host

import hivens.widget.api.HostAccess
import hivens.widget.api.LevelServices
import hivens.widget.api.WidgetScope

/**
 * The launcher's key to the host view of the levels it makes. Every app level the
 * launcher builds is made with it, and the launcher's own code reaches its services
 * under a level through it. Internal to the host, so a module has no way to it that
 * the API offers.
 */
internal val LauncherHostAccess: HostAccess = HostAccess()

/**
 * This level's services as the host sees them: its own and every one modules
 * declared. A tree the launcher did not make with its access, which only a test
 * builds, answers what every module sees.
 */
internal val WidgetScope.launcherServices: LevelServices
    get() = LauncherHostAccess.servicesOf(this) ?: services
