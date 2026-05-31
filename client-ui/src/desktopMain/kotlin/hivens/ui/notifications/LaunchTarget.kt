package hivens.ui.notifications

import hivens.core.api.model.ServerProfile
import hivens.core.data.PackInstance

/**
 * Source-neutral abstraction over the two kinds of things the launcher
 * spawns: a `PackInstance` (Hivens / mirror-curated pack with its own
 * instance dir) and a `ServerProfile` (SC server-list entry that shares
 * a client root with other SC servers of the same modset).
 *
 * Both flows go through `LauncherController` and emit the same
 * `LaunchState` shape, so the notification driver that observes them
 * does not need separate code paths -- it only needs the target's
 * stable id, the display label, and the source-key prefix that
 * groups notifications.
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

    data class Server(val server: ServerProfile) : LaunchTarget {
        // The assetDir is the SC-internal identifier shared across the
        // launcher (manifest cache, sync state, lookup); use it as the
        // stable id so independent surfaces converge on the same row.
        override val id          get() = server.assetDir
        override val displayName get() = server.title?.ifBlank { null } ?: server.name
        // ServerProfile has no icon field today; same posture as Pack.
        override val iconUrl     get(): String? = null
        override val sourceKey   get() = "server:${server.assetDir}:launch"
    }
}
