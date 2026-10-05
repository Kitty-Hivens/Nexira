package hivens.core.api.interfaces

import hivens.core.data.SessionData

/**
 * The view of the persisted player session the launch flow consumes. Narrowing
 * the controller to reads, plus the one write [refreshStored], and giving the
 * auth/mirror extraction a seam to swap is the point -- the launcher's
 * credential manager implements it.
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

    /**
     * Brings an account already stored for [providerId] up to [session], minted by
     * a sign-in the launch made itself. Stores nothing for an account that is not
     * stored, so a sign-in the user chose not to remember stays unremembered, and
     * never changes which account is active.
     *
     * A SmartyCraft login mints a new uid and retires the previous one, so a store
     * left on the uid of its first sign-in signs every later action, a skin upload
     * among them, with one the server has already dropped.
     */
    fun refreshStored(providerId: String, session: SessionData)
}
