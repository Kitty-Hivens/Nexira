package hivens.ui.screens.settings

import hivens.ui.i18n.AppStrings
import hivens.ui.icons.IconKey
import hivens.ui.icons.NxIcon

/**
 * Top-level Settings categories used by the two-column layout (left nav
 * + right content). Each value carries the icon for the nav row and the
 * accessor that pulls the localised label out of [AppStrings]; that
 * way the i18n bundle stays the single source of label text, and the
 * enum doesn't need an enum-class-of-localised-strings dance.
 *
 * Order here = display order in the nav.
 */
internal enum class SettingsCategory(
    val icon: IconKey,
    val label: (AppStrings) -> String,
) {
    Appearance(   NxIcon.Palette,   { it.settingsCategoryAppearance }),
    Console(      NxIcon.Code,      { it.settingsCategoryConsole }),
    Network(      NxIcon.Wifi,      { it.settingsCategoryNetwork }),
    Smarty(       NxIcon.Shield,    { it.settingsCategorySmarty }),
    Advanced(     NxIcon.Folder,    { it.settingsCategoryAdvanced }),
    Diagnostics(  NxIcon.BugReport, { it.settingsCategoryDiagnostics }),
}
