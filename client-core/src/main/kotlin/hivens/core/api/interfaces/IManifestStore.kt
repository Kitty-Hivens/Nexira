package hivens.core.api.interfaces

import hivens.core.data.FileManifest

/**
 * Reads a cached file manifest from a prior online sync. The launch flow falls
 * back to it for 2FA accounts and offline relaunches; the controller injects
 * this read-only slice of the launcher's manifest cache.
 */
interface IManifestStore {
    fun loadManifest(serverId: String): FileManifest?
}
