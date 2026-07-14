package hivens.core.smrt

import hivens.core.api.dto.smrt.SmrtModEntry

/**
 * Buckets a manifest's mods by `display.role` for the Library PackDetail
 * Content tab. Same role across multiple mods = interchangeable role
 * fillers (Recipe viewer: JEI / REI / EMI), rendered as a dropdown
 * picker. Mods with no role land in [ModGrouping.ungrouped] and render
 * as plain rows.
 *
 * Role keys are case-folded to lowercase so a mirror author who
 * writes "Recipe_Viewer" gets grouped with one who wrote "recipe_viewer".
 * Empty/blank roles count as ungrouped.
 */
object ModRoleGrouper {

    fun group(mods: List<SmrtModEntry>): ModGrouping {
        val byRole = linkedMapOf<String, MutableList<SmrtModEntry>>()
        val ungrouped = mutableListOf<SmrtModEntry>()

        for (mod in mods) {
            val role = mod.display?.role?.trim()?.lowercase()
            if (role.isNullOrBlank()) {
                ungrouped += mod
            } else {
                byRole.getOrPut(role) { mutableListOf() } += mod
            }
        }

        return ModGrouping(
            byRole = byRole.map { (role, members) -> ModRoleGroup(role = role, members = members) },
            ungrouped = ungrouped,
        )
    }
}

data class ModRoleGroup(
    /** Lowercased role key. UI maps it to a localised label (`s.modRoleRecipeViewer` etc) when known. */
    val role: String,
    /** Members in manifest order. UI's default-pick is the first; user can override via the dropdown. */
    val members: List<SmrtModEntry>,
)

data class ModGrouping(
    val byRole: List<ModRoleGroup>,
    val ungrouped: List<SmrtModEntry>,
)
