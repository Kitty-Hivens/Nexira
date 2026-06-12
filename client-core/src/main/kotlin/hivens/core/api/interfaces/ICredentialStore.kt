package hivens.core.api.interfaces

import hivens.core.data.SessionData

/**
 * Read-only view of the persisted player session the launch flow consumes.
 * Narrowing the controller to a read (it never writes or clears credentials)
 * and giving the auth/mirror extraction a seam to swap is the point -- the
 * launcher's credential manager implements it.
 */
interface ICredentialStore {
    fun load(): SessionData?
}
