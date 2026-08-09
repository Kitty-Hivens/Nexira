package hivens.ui.widgets.profile

import hivens.core.data.PackAuthRequirement
import hivens.ui.i18n.AppStrings
import hivens.ui.icons.IconKey
import hivens.ui.icons.NxIcon

// Top-level Profile categories -- one per credential provider. The nav widget
// renders one row per entry; the surface composable routes the right pane's slot
// by the active entry. Each section owns its provider's whole story: signed in
// it shows that account (and a sign-out), signed out it shows that provider's
// sign-in. The two protocols differ deeply, so the sections share no layout --
// SmartyCraft carries balance/top-up/skin, Microsoft the Mojang profile + skins.
//
// Labels are the providers' own names (proper nouns), identical across locales,
// so they are literal rather than localized. Order = display order in the nav.
enum class ProfileCategory(
    val icon: IconKey,
    val label: (AppStrings) -> String,
    /** The credential provider this category speaks for, as stored on an account. */
    val providerKey: String,
) {
    SmartyCraft(NxIcon.AccountCircle, { "SmartyCraft" }, PackAuthRequirement.SmartyCraft.PROVIDER_KEY),
    Microsoft(  NxIcon.Public,        { "Microsoft" },   PackAuthRequirement.Microsoft.PROVIDER_KEY),
}
