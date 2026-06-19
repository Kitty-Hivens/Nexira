package hivens.core.api.interfaces

import hivens.core.data.SessionData

/**
 * Read-only view of the persisted player session the launch flow consumes.
 * Narrowing the controller to a read (it never writes or clears credentials)
 * and giving the auth/mirror extraction a seam to swap is the point -- the
 * launcher's credential manager implements it.
 */
interface ICredentialStore {
    /** The active account's session, or null when none is signed in. */
    fun load(): SessionData?

    /**
     * The session for [providerId]'s account, or null when not signed in with
     * that provider. Lets the launch pick the account matching the content's
     * required provider (multi-active: SC + Microsoft + offline coexist).
     */
    fun accountFor(providerId: String): SessionData?
}
