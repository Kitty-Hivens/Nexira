package hivens.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import hivens.ui.AppState
import hivens.ui.i18n.LocalStrings
import hivens.ui.puppet.PuppetClick
import hivens.ui.widgets.Commands
import hivens.ui.widgets.Sources
import hivens.widget.api.LocalScope
import hivens.widget.api.find
import hivens.widget.api.rememberScopedAction
import hivens.widget.api.rememberScopedSource
import hivens.widget.api.valueOrNull
import hivens.widget.model.ScopeLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A sign-out asked for in one window and not yet answered.
 *
 * One per window, a service of the window's level, so the question outlives the
 * widget that asked it. It has to: the widget that offers sign-out is often one the
 * answer takes away, an account section that leaves once its account is gone, and a
 * question owned by that widget went with it, leaving an account removed and the
 * session standing with no confirmation ever seen.
 */
class SignOutRequests {
    private val _asking = MutableStateFlow(false)

    /** True while a sign-out waits for the person's answer. */
    val asking: StateFlow<Boolean> = _asking.asStateFlow()

    /** Asks whether to sign out. Asking again while the question is open changes nothing. */
    fun ask() {
        _asking.value = true
    }

    /** Takes the question away, answered or not. */
    fun close() {
        _asking.value = false
    }
}

/** The window's [SignOutRequests], or null outside any window the launcher drew. */
@Composable
private fun rememberSignOutRequests(): SignOutRequests? {
    val window = LocalScope.current?.nearest(ScopeLevel.Window)
    return remember(window) { window?.services?.find<SignOutRequests>() }
}

/**
 * Sign-out the way a widget offers it: the returned call asks the person first, and
 * [Commands.SignOut] runs only on a yes. Null where the command or the window's
 * question has no answer.
 *
 * The question belongs to the window, not to the caller, see [SignOutRequests], and
 * [SignOutPrompt] puts it on the window's layers.
 */
@Composable
fun rememberSignOutRequest(): (() -> Unit)? {
    val requests = rememberSignOutRequests() ?: return null
    val _ = rememberScopedAction(Commands.SignOut) ?: return null
    return remember(requests) { requests::ask }
}

/**
 * Draws the window's open sign-out question, and signs out on a yes.
 *
 * Drawn once per window, by whoever draws the window's content, inside its theme
 * and its layers. The question closes itself once nobody is signed in, so a
 * sign-out made somewhere else does not leave it asking about a session that is gone.
 *
 * Automation answers through `logout.confirm`, registered for as long as this is
 * drawn rather than only while the dialog is: it signs out at once, the question
 * open or not, the way the two-step sign-out was always driven.
 */
@Composable
fun SignOutPrompt() {
    val requests = rememberSignOutRequests() ?: return
    val signOut = rememberScopedAction(Commands.SignOut)
    val asking by requests.asking.collectAsState()
    val signedIn = rememberScopedSource(Sources.Session).valueOrNull() is AppState.Authenticated
    LaunchedEffect(signedIn) {
        if (!signedIn) requests.close()
    }
    PuppetClick("logout.confirm") {
        requests.close()
        signOut?.invoke()
    }
    if (asking && signOut != null) {
        val s = LocalStrings.current
        DestructiveConfirmDialog(
            title        = s.logoutConfirmTitle,
            body         = s.logoutConfirmBody,
            confirmLabel = s.navLogout,
            onConfirm    = signOut,
            onDismiss    = requests::close,
        )
    }
}
