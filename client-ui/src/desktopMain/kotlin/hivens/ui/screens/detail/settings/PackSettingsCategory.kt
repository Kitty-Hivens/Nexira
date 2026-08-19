package hivens.ui.screens.detail.settings

import hivens.ui.i18n.AppStrings
import hivens.ui.icons.IconKey
import hivens.ui.icons.NxIcon

/**
 * Sections of the floating pack-settings window, in rail order. Mirrors the
 * global [hivens.ui.screens.settings.SettingsCategory] grammar: an icon plus an
 * i18n label accessor, order here = display order in the rail.
 *
 * [mirrorOnly] hides a section for non-mirror instances -- Version/updates has
 * no meaning for a Local or imported pack, so it is absent from the rail there.
 */
enum class PackSettingsCategory(
    val icon: IconKey,
    val label: (AppStrings) -> String,
    val mirrorOnly: Boolean = false,
) {
    General(NxIcon.Tune, { it.packSettingsCategoryGeneral }),
    Runtime(NxIcon.Memory, { it.packSettingsCategoryRuntime }),
    Version(NxIcon.Update, { it.packSettingsCategoryVersion }, mirrorOnly = true),
    Content(NxIcon.Layers, { it.packSettingsCategoryContent }),
    Data(NxIcon.Storage, { it.packSettingsCategoryData }),
}
