package hivens.ui.screens.settings

import hivens.i18n.api.Strings
import hivens.ui.icons.IconKey
import hivens.ui.icons.NxIcon

/**
 * Top-level Settings categories used by the two-column layout (left nav
 * + right content). Each value carries the icon for the nav row and the
 * accessor that pulls the localised label out of [Strings]. That way the
 * launcher's word tables stay the single source of label text, and the
 * enum doesn't need an enum-class-of-localised-strings dance.
 *
 * Order here = display order in the nav.
 */
internal enum class SettingsCategory(
    val icon: IconKey,
    val label: (Strings) -> String,
) {
    Appearance(   NxIcon.Palette,   { it.text("settingsCategoryAppearance") }),
    Console(      NxIcon.Code,      { it.text("settingsCategoryConsole") }),
    Network(      NxIcon.Wifi,      { it.text("settingsCategoryNetwork") }),
    Smarty(       NxIcon.Shield,    { it.text("settingsCategorySmarty") }),
    Advanced(     NxIcon.Folder,    { it.text("settingsCategoryAdvanced") }),
    Diagnostics(  NxIcon.BugReport, { it.text("settingsCategoryDiagnostics") }),
}
