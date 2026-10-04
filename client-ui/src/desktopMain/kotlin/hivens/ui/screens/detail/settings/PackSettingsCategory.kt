package hivens.ui.screens.detail.settings

import hivens.ui.i18n.AppStrings
import hivens.ui.icons.IconKey
import hivens.ui.icons.NxIcon

/**
 * Sections of the pack-settings sheet, in tab order. Mirrors the global
 * [hivens.ui.screens.settings.SettingsCategory] grammar: an icon plus an i18n
 * label accessor, order here = display order.
 *
 * [needsVersionFeed] hides a section for an instance whose source cannot offer
 * other builds: version management has nothing to manage for a local or imported
 * pack, so it is absent there. Deliberately a capability and not an origin -- it
 * read "mirror only" while the mirror was the only source that could update, and
 * stayed that way after another one learned to, which is how a Modrinth pack ended
 * up able to switch versions with no way to say so.
 *
 * [needsOptionalContent] hides a section that only a curated manifest can fill:
 * optional mods are declared by the mirror, and a pack from anywhere else showed a
 * tab whose whole content was the sentence saying it had none.
 */
enum class PackSettingsCategory(
    val icon: IconKey,
    val label: (AppStrings) -> String,
    val needsVersionFeed: Boolean = false,
    val needsOptionalContent: Boolean = false,
) {
    General(NxIcon.Tune, { it.packSettingsCategoryGeneral }),
    Runtime(NxIcon.Memory, { it.packSettingsCategoryRuntime }),
    Version(NxIcon.Update, { it.packSettingsCategoryVersion }, needsVersionFeed = true),
    Content(NxIcon.Layers, { it.packSettingsCategoryContent }, needsOptionalContent = true),
    Data(NxIcon.Storage, { it.packSettingsCategoryData }),
}
