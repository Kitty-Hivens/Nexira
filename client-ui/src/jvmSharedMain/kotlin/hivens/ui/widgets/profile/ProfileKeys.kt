package hivens.ui.widgets.profile

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import hivens.auth.AuthProviderRegistry
import hivens.widget.model.ScopeLevel
import hivens.widget.model.SourceKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

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

/**
 * Counts the accounts signed in or out since the launcher started, for the readers
 * that have to look at the store again when one is. One for the app, because an
 * account is the app's and every window showing a list of them has to hear it.
 */
class AccountChanges {
    private val count = MutableStateFlow(0)

    val revision: StateFlow<Int> = count.asStateFlow()

    fun changed() {
        count.update { it + 1 }
    }
}

// Microsoft / multi-account is deferred: it is gated on a registered device-code
// provider, which only joins the registry when a Microsoft client id is configured.
// Single-sourced here so the profile nav and the sign-in section share one gate.
internal fun AuthProviderRegistry.hasDeviceCodeProvider(): Boolean =
    all.any { it.capabilities.supportsDeviceCode }
