package hivens.ui.widgets.profile

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import hivens.accounts.api.ProviderView
import hivens.widget.model.ScopeLevel
import hivens.widget.model.SourceKey

/**
 * Which section the profile page shows. The nav writes it on a tap and the surface
 * reads it to pick the slot it draws. One per page, made by the page's level, so a
 * profile page kept in the history comes back on the section it was left on.
 */
@Stable
internal class ProfileSelection {
    // Per-provider sections each own their signed-in/out state, so the category
    // does not flip with the session; the SmartyCraft section comes first.
    var category by mutableStateOf(ProfileCategory.SmartyCraft)
}

internal val ProfileSelectionSource: SourceKey<ProfileSelection> = SourceKey("profile.category", ScopeLevel.Page)

// Microsoft / multi-account is deferred: it is gated on a device-code provider, which
// the accounts module lists only when a Microsoft client id is configured.
// Single-sourced here so the profile nav and the sign-in section share one gate.
internal fun List<ProviderView>.hasDeviceCodeProvider(): Boolean =
    any { it.kind == ProviderView.DEVICE_CODE }
