package hivens.core.update

/**
 * A retained pre-update snapshot of a pack instance the user can roll back to.
 * Metadata only; the captured bytes and the pre-update instance record live on
 * disk under the launcher's snapshot area. [fromVersion] is the build the
 * instance was on when the snapshot was taken.
 */
data class PackSnapshot(
    val id: String,
    val createdAtEpoch: Long,
    val fromVersion: String?,
)
