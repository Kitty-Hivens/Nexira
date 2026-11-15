package hivens.ui.widgets.profile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import hivens.accounts.api.AccountKeys
import hivens.accounts.api.AccountRemoval
import hivens.accounts.api.RemovalOutcome
import hivens.ui.components.rememberSignOutRequest
import hivens.widget.api.LocalScope
import hivens.widget.api.rememberScopedCommand
import hivens.widget.model.ScopeLevel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Signing out of one provider's account, the way a profile section offers it.
 *
 * For the last account it asks the window's sign-out question at once, since that is
 * a sign-out of everything. With another account left it removes this one and the
 * launcher moves to the face that is left, and where the store then gives no face, a
 * keyring that failed half way say, it asks the same question rather than clear
 * anything more without the person's word.
 *
 * The outcome is waited for on the window's work, not the section's: the section
 * leaves as soon as its account is gone, and the question has to be asked all the
 * same. The question itself belongs to the window, see [rememberSignOutRequest].
 *
 * Null where a key it needs or the window's question has no answer.
 */
@Composable
internal fun rememberAccountSignOut(providerKey: String, othersLeft: Boolean): (() -> Unit)? {
    val remove = rememberScopedCommand(AccountKeys.RemoveAccount) ?: return null
    val signOutAll = rememberSignOutRequest() ?: return null
    val level = LocalScope.current
    val window = level?.nearest(ScopeLevel.Window) ?: return null
    val removals = remember(level) { level.find(AccountKeys.Removals)?.state } ?: return null
    val others = rememberUpdatedState(othersLeft)
    val removeNow = rememberUpdatedState(remove)
    val ask = rememberUpdatedState(signOutAll)
    return remember(providerKey, window, removals) {
        {
            if (!others.value) {
                ask.value()
            } else {
                val ticket = UUID.randomUUID().toString()
                removeNow.value(AccountRemoval(providerKey, ticket))
                window.coroutineScope.launch {
                    // A removal of this provider already under way takes this one's
                    // place, and its end is this one's end too.
                    val ended = removals.first { it[providerKey]?.outcome.let { o -> o != null && o != RemovalOutcome.Removing } }
                    if (ended.getValue(providerKey).outcome == RemovalOutcome.NoFaceLeft) ask.value()
                }
            }
        }
    }
}
