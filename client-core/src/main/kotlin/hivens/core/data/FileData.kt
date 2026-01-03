package hivens.core.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Модель данных (DTO) для отдельного файла в манифесте.
 */
@Serializable
data class FileData(
    @SerialName("md5")
    val md5: String = "",

    @SerialName("size")
    val size: Long = 0
)
