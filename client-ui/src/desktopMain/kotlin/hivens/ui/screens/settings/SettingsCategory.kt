package hivens.ui.screens.settings

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.ui.graphics.vector.ImageVector
import hivens.ui.i18n.AppStrings

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
    val icon: ImageVector,
    val label: (AppStrings) -> String,
) {
    Appearance(   Icons.Default.Palette,   { it.settingsCategoryAppearance }),
    Network(      Icons.Default.Wifi,      { it.settingsCategoryNetwork }),
    Experimental( Icons.Default.Science,   { it.settingsCategoryExperimental }),
    Advanced(     Icons.Default.Folder,    { it.settingsCategoryAdvanced }),
    Diagnostics(  Icons.Default.BugReport, { it.settingsCategoryDiagnostics }),
}
