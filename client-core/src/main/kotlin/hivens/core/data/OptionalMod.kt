package hivens.core.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Продвинутая модель опциональной модификации.
 */
@Serializable
data class OptionalMod(
    var id: String = "",
    var name: String = "",
    var description: String? = null,
    var category: String? = null,
    var infoFile: String? = null,
    var jars: List<String> = emptyList(),
    var excludings: List<String> = emptyList(),
    var incompatibleIds: List<String> = emptyList(),
    @SerialName("selected")
    private val _isSelected: Boolean? = null,
    @SerialName("default")
    private val _isDefault: Boolean? = null
) {
    val isDefault: Boolean
        get() = _isSelected ?: _isDefault ?: false
}
