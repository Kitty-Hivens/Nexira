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
    val size: Long = 0
)
