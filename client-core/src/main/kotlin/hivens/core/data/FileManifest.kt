package hivens.core.data

import com.google.gson.annotations.SerializedName

/**
 * Модель данных (DTO) для манифеста файлов клиента.
 */
data class FileManifest(
    @SerializedName("directories")
    val directories: Map<String, FileManifest> = emptyMap(),

    @SerializedName("files")
    val files: Map<String, FileData> = emptyMap()
)
