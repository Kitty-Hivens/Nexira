package hivens.core.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Data model (DTO) for the client file manifest.
 */
@Serializable
data class FileManifest(
    @SerialName("directories")
    val directories: Map<String, FileManifest> = emptyMap(),

    @SerialName("files")
    val files: Map<String, FileData> = emptyMap()
)
