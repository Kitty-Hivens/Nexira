package hivens.packs

import hivens.core.data.PackInstance
import hivens.core.data.SessionData
import hivens.packs.api.PackLaunch
import kotlinx.coroutines.flow.StateFlow

/**
 * What the packs module needs from the application around it and does not own yet:
 * who a launch runs as, the per-pack launch notices the application keeps, and
 * where to say that a pack asked for is not installed.
 *
 * The host provides one as a service. Each part is meant to move into a module of
 * its own (the session into the accounts module, the launch notices into this one),
 * and this seam shrinks as they do.
 */
interface PacksHost {
    /** Who a launch started now runs as, read at the moment it starts. */
    val identity: LaunchIdentity

    /** Where the launch of each pack stands, by pack id, as the application's launch driver keeps it. */
    val launches: StateFlow<Map<String, PackLaunch>>

    /** A launch of [pack] started, for the application to follow with its notices. */
    fun launched(pack: PackInstance)

    /** A pack of id [packId] was asked for and is not installed. Said out loud by the host. */
    fun packMissing(packId: String)

    /** A pack of id [packId] was launched, so a notice that it was missing no longer holds. */
    fun packFound(packId: String)
}

/** Who a launch runs as. */
sealed interface LaunchIdentity {
    /** The startup sign-in is still finding out. A launch now is refused rather than run as the wrong person. */
    data object Resolving : LaunchIdentity

    /** Nobody is signed in, so a launch runs as the offline name, when there is one. */
    data object Nobody : LaunchIdentity

    /** Signed in as [session]. */
    data class SignedIn(val session: SessionData) : LaunchIdentity
}
