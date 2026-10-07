package hivens.ui.notifications

import hivens.core.data.PackInstance

/**
 * What the launcher spawns, as the notification driver needs to see it: a
 * stable id, a display label, and the source-key prefix that groups
 * notifications.
 *
 * A class and not a sealed interface, because there is one thing the launcher
 * spawns. It was an interface while a SmartyCraft server could also be launched;
 * with that gone, the pack IS the unit of content, and every entry point --
 * a pack's page, quick launch, a relaunch from a notification, the CLI -- hands
 * over a [PackInstance]. Microsoft is a requirement ON a pack rather than a
 * second kind of target, so it changes nothing here.
 *
 * The interface that survived the removal was over a single case, and the driver
 * saw through it anyway, reaching for the instance three times to do its work.
 * An abstraction nobody can be held to is not one.
 */
data class LaunchTarget(val instance: PackInstance) {
    val id: String get() = instance.id
    val displayName: String get() = instance.displayName

    // PackInstance does not carry icon_url yet; surfaces null until
    // project_pack_rich_metadata propagates summary.icon_url.
    val iconUrl: String? get() = null

    val sourceKey: String get() = "pack:${instance.id}:launch"
}
