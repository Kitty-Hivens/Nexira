package hivens.ui.widgets.profile

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Person
import androidx.compose.ui.graphics.vector.ImageVector
import hivens.ui.i18n.AppStrings

// Top-level Profile categories. The nav widget renders one row per
// entry; the surface composable routes the right pane's slot
// selection based on the active entry. Order = display order in
// the nav.
enum class ProfileCategory(
    val icon: ImageVector,
    val label: (AppStrings) -> String,
) {
    Skin(    Icons.Default.Person,        { it.profileCategorySkin }),
    Account( Icons.Default.AccountCircle, { it.profileCategoryAccount }),
}
