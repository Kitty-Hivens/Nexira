package hivens.module.accountsconsumer

import androidx.compose.runtime.Composable
import hivens.accounts.api.AccountKeys
import hivens.accounts.api.SessionState
import hivens.accounts.api.SessionView
import hivens.widget.api.KeyContribution
import hivens.widget.api.ModuleKeys
import hivens.widget.api.flowSource
import hivens.widget.api.rememberScopedAction
import hivens.widget.api.rememberScopedSource
import hivens.widget.api.valueOrNull
import hivens.widget.model.SourceKey
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * The keys this module answers. A class with a public constructor, because a jar's
 * contributions are made by the service loader.
 */
class GreetingKeys : KeyContribution {
    override val id: String = "accounts-consumer.keys"
    override val label: String = "Greeting"

    companion object {
        /**
         * A greeting for whoever the launcher is signed in as, or null while nobody
         * is. Made from the accounts module's session, and missing where that module is.
         */
        val Greeting: SourceKey<String?> = SourceKey("accounts-consumer.greeting")
    }

    override fun declare(keys: ModuleKeys) {
        keys.source(Greeting, sample = { "Hello, Steve" }) { app ->
            val session = checkNotNull(app.find(AccountKeys.Session)) { "the accounts module is not brought in" }.state
            flowSource(session.map(::greeting).stateIn(app.coroutineScope, SharingStarted.Eagerly, greeting(session.value)))
        }
    }

    private fun greeting(state: SessionState): String? = (state as? SessionState.SignedIn)?.session?.let { "Hello, ${it.name}" }
}

/**
 * A widget body over the accounts keys: who is signed in, and a sign-out for them.
 * What it read and the sign-out it got are handed to [seen], so a test can see them.
 */
@Composable
fun SessionBadge(seen: (session: SessionView?, signOut: (() -> Unit)?) -> Unit) {
    val state = rememberScopedSource(AccountKeys.Session).valueOrNull()
    val signOut = rememberScopedAction(AccountKeys.SignOut)
    val session = (state as? SessionState.SignedIn)?.session
    seen(session, if (session != null) signOut else null)
}
