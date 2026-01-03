package hivens.core.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Модель данных (DTO) для манифеста файлов клиента.
 */
@Serializable
data class FileManifest(
    @SerialName("directories")
    val directories: Map<String, FileManifest> = emptyMap(),

    @SerialName("files")
    val files: Map<String, FileData> = emptyMap()
)
