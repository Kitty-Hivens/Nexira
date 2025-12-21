package hivens.core.data

import com.google.gson.annotations.SerializedName

/**
 * Продвинутая модель опциональной модификации.
 */
data class OptionalMod(
    var id: String = "",
    var name: String = "",
    var description: String? = null,
    var category: String? = null,
    var jars: MutableList<String> = ArrayList(),
    var excludings: MutableList<String> = ArrayList(),
    var incompatibleIds: MutableList<String> = ArrayList(),
    @SerializedName("default")
    var isDefault: Boolean = false
)
