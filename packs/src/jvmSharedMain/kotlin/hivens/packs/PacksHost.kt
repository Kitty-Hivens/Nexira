package hivens.packs

import hivens.core.data.PackInstance
import hivens.packs.api.PackLaunch
import kotlinx.coroutines.flow.StateFlow

/**
 * What the packs module needs from the application around it and does not own yet:
 * the per-pack launch notices the application keeps, and where to say that a pack
 * asked for is not installed.
 *
 * The host provides one as a service. The launch notices are meant to move into this
 * module, and this seam shrinks as they do. Who a launch runs as is the accounts
 * module's to say, see `hivens.accounts.LaunchSessions`.
 */
interface PacksHost {
    /** Where the launch of each pack stands, by pack id, as the application's launch driver keeps it. */
    val launches: StateFlow<Map<String, PackLaunch>>

    /** A launch of [pack] started, for the application to follow with its notices. */
    fun launched(pack: PackInstance)

    /** A pack of id [packId] was asked for and is not installed. Said out loud by the host. */
    fun packMissing(packId: String)

    /** A pack of id [packId] was launched, so a notice that it was missing no longer holds. */
    fun packFound(packId: String)
}
