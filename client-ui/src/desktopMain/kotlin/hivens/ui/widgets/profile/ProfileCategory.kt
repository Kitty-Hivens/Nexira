package hivens.ui.widgets.profile

import hivens.ui.i18n.AppStrings
import hivens.ui.icons.IconKey
import hivens.ui.icons.NxIcon

// Top-level Profile categories. The nav widget renders one row per
// entry; the surface composable routes the right pane's slot
// selection based on the active entry. Order = display order in the
// nav. SignIn renders without a session (the login form when signed
// out, credential management when signed in); Account needs an
// identity and the nav hides it while signed out. The skin lives on
// the Account tab (skin-forward); a dedicated skin screen comes later.
enum class ProfileCategory(
    val icon: IconKey,
    val label: (AppStrings) -> String,
) {
    Account( NxIcon.AccountCircle, { it.profileCategoryAccount }),
    SignIn(  NxIcon.Lock,          { it.profileCategorySignIn }),
}
