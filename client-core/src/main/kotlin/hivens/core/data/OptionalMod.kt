package hivens.core.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Advanced optional modification model.
 */
@Serializable
data class OptionalMod(
    val id: String = "",
    val name: String = "",
    val description: String? = null,
    val category: String? = null,
    val infoFile: String? = null,
    val jars: List<String> = emptyList(),
    val excludings: List<String> = emptyList(),
    val incompatibleIds: List<String> = emptyList(),
    @SerialName("selected")
    private val _isSelected: Boolean? = null,
    @SerialName("default")
    private val _isDefault: Boolean? = null,
) {
    val isDefault: Boolean
        get() = _isSelected ?: _isDefault ?: false
}
