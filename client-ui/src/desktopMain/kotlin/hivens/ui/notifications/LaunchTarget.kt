package hivens.ui.notifications

import hivens.core.data.PackInstance

/**
 * What the launcher spawns, as the notification driver needs to see it: a
 * stable id, a display label, and the source-key prefix that groups
 * notifications.
 *
 * A class and not a sealed interface. It was one while the launcher could also
 * spawn a SmartyCraft server, and keeping the shape after that left an interface
 * over a single case -- which the driver saw through anyway, reaching for the
 * instance three times to do its work. An abstraction nobody can be held to is
 * not an abstraction. When something else becomes launchable, this becomes an
 * interface again, and that is a smaller change than the vacuous branches were
 * a cost.
 */
data class LaunchTarget(val instance: PackInstance) {
    val id: String get() = instance.id
    val displayName: String get() = instance.displayName

    // PackInstance does not carry icon_url yet; surfaces null until
    // project_pack_rich_metadata propagates summary.icon_url.
    val iconUrl: String? get() = null

    val sourceKey: String get() = "pack:${instance.id}:launch"
}
