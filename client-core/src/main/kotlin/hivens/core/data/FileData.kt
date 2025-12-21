package hivens.core.data

import com.google.gson.annotations.SerializedName

/**
 * Модель данных (DTO) для отдельного файла в манифесте.
 */
data class FileData(
    @SerializedName("md5")
    val md5: String = "",

    @SerializedName("size")
    val size: Long = 0
)
