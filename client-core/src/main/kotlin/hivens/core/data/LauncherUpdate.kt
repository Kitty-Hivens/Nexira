package hivens.core.data

import kotlinx.serialization.Serializable

/**
 * Information about available launcher updates.
 */
@Serializable
data class LauncherUpdate(
    val version: String,
    val downloadUrl: String,
    val checksum: String,
    val changelog: String,
    val isCritical: Boolean
)
