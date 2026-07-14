package hivens.core.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Data model (DTO) for a separate file in the manifest.
 */
@Serializable
data class FileData(
    @SerialName("md5")
    val md5: String = "",

    @SerialName("size")
    val size: Long = 0,

    /**
     * SHA-1 of the file. The launcher's own release manifest hashes with [md5];
     * the per-instance pack baseline ([PackInstance.installedManifest]) hashes with
     * sha1 -- that is what mirror / mrpack manifests already carry, so the install
     * flow records it for free and the [hivens.core.update.UpdateReconciler] can
     * diff target vs on-disk without re-hashing. Empty when not captured.
     */
    @SerialName("sha1")
    val sha1: String = ""
)
