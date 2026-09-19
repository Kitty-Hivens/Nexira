package hivens.ui.notifications

import hivens.core.data.PackInstance

/**
 * What the launcher spawns, as the notification driver needs to see it: a
 * stable id, a display label, and the source-key prefix that groups
 * notifications.
 *
 * An interface over one case today. It stays one because the driver has no
 * business knowing what a launch target IS -- the pack is the unit of content,
 * and anything else the launcher learns to spawn plugs in here rather than
 * forking the driver.
 */
sealed interface LaunchTarget {
    val id: String
    val displayName: String
    val iconUrl: String?
    val sourceKey: String

    data class Pack(val instance: PackInstance) : LaunchTarget {
        override val id          get() = instance.id
        override val displayName get() = instance.displayName
        // PackInstance does not carry icon_url yet; surfaces null until
        // project_pack_rich_metadata propagates summary.icon_url.
        override val iconUrl     get(): String? = null
        override val sourceKey   get() = "pack:${instance.id}:launch"
    }
}
