package hivens.ui.screens.detail.settings

import hivens.ui.i18n.AppStrings
import hivens.ui.icons.IconKey
import hivens.ui.icons.NxIcon

/**
 * Sections of the floating pack-settings window, in rail order. Mirrors the
 * global [hivens.ui.screens.settings.SettingsCategory] grammar: an icon plus an
 * i18n label accessor, order here = display order in the rail.
 *
 * [needsVersionFeed] hides a section for an instance whose source cannot offer
 * other builds: version management has nothing to manage for a local or imported
 * pack, so it is absent from the rail there. Deliberately a capability and not an
 * origin -- it read "mirror only" while the mirror was the only source that could
 * update, and stayed that way after another one learned to, which is how a
 * Modrinth pack ended up able to switch versions with no way to say so.
 */
enum class PackSettingsCategory(
    val icon: IconKey,
    val label: (AppStrings) -> String,
    val needsVersionFeed: Boolean = false,
) {
    General(NxIcon.Tune, { it.packSettingsCategoryGeneral }),
    Runtime(NxIcon.Memory, { it.packSettingsCategoryRuntime }),
    Version(NxIcon.Update, { it.packSettingsCategoryVersion }, needsVersionFeed = true),
    Content(NxIcon.Layers, { it.packSettingsCategoryContent }),
    Data(NxIcon.Storage, { it.packSettingsCategoryData }),
}
