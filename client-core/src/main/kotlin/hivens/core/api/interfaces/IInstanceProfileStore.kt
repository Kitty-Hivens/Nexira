package hivens.core.api.interfaces

import hivens.core.data.InstanceProfile

/**
 * Per-server instance profile lookup. The launch flow reads it to compute
 * ignored optional mods; the controller injects this slice of the launcher's
 * profile manager.
 */
interface IInstanceProfileStore {
    fun getProfile(serverId: String): InstanceProfile
}
