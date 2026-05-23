package hivens.ui.screens.profile

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Person
import androidx.compose.ui.graphics.vector.ImageVector
import hivens.ui.i18n.AppStrings

/**
 * Top-level Profile categories used by the two-column layout
 * (left nav + right content). Order = display order in the nav.
 */
internal enum class ProfileCategory(
    val icon: ImageVector,
    val label: (AppStrings) -> String,
) {
    Skin(    Icons.Default.Person,        { it.profileCategorySkin }),
    Account( Icons.Default.AccountCircle, { it.profileCategoryAccount }),
}
