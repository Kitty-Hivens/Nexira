package hivens.core.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One optional modification a SmartyCraft server offers, as its dashboard
 * declares it.
 *
 * Upstream sends two separate on/off fields. `selected` is what the account
 * picked on the dashboard, `default` is what the server ships for an account
 * that never picked; either can be absent. Both are kept as they arrive, so an
 * account that deselected something the server defaults on is representable,
 * and [enabledByDefault] answers the only question the launcher asks of them:
 * whether this mod is on when the local profile holds no opinion.
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
    /**
     * Whether the mod is on for an account with no local choice recorded: the
     * dashboard selection when there is one, else what the server ships, else
     * off. Named for what it answers rather than for the field it happens to
     * read -- it is usually `selected`, which is not the default at all.
     */
    val enabledByDefault: Boolean
        get() = _isSelected ?: _isDefault ?: false
}
