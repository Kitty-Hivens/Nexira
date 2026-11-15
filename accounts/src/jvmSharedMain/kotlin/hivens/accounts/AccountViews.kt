package hivens.accounts

import hivens.accounts.api.AccountView
import hivens.accounts.api.SessionView
import hivens.auth.AccountStore
import hivens.auth.OfflineAuthProvider
import hivens.auth.StoredAccount
import hivens.core.data.SessionData

/** [this] as a widget may read it, an account of [provider]: the name and the uuid, nothing secret. */
fun SessionData.toView(provider: String): SessionView =
    SessionView(name = playerName, uuid = uuid, providerKey = provider, offline = offline)

/**
 * The provider whose stored account [session] is, matched by uuid and then by name,
 * or [guess] where no stored account matches. An offline identity is offline's. Reads
 * the account list, so off the main thread.
 */
fun providerOf(session: SessionData, accounts: AccountStore, guess: (SessionData) -> String): String {
    if (session.offline) return OfflineAuthProvider.PROVIDER_KEY
    val stored = accounts.listAccounts()
    return stored.firstOrNull { session.uuid.isNotBlank() && it.uuid == session.uuid }?.providerId
        ?: stored.firstOrNull { it.username == session.playerName }?.providerId
        ?: guess(session)
}

/**
 * [account] as a widget reads it, with what [session], its stored session, says of
 * the provider's side: the balance, the clan, and whether it holds a token at all.
 * The token itself stays here.
 */
internal fun accountView(account: StoredAccount, session: SessionData? = null): AccountView =
    AccountView(
        providerKey  = account.providerId,
        accountId    = account.accountId,
        name         = session?.playerName?.takeIf { it.isNotBlank() } ?: account.username.ifBlank { account.displayName },
        uuid         = session?.uuid?.takeIf { it.isNotBlank() } ?: account.uuid,
        balance      = session?.balance ?: 0,
        clan         = session?.clan,
        clanResolved = session?.clanResolved ?: false,
        // The profile has always called a token this short no session at all.
        online       = (session?.accessToken?.length ?: 0) > 10,
    )

/**
 * Whether a login that returned this session, rather than raising a second-factor
 * demand, shows the account no longer answers to one.
 *
 * Only a session that is not itself the product of a code does. The provider keeps
 * the session a code unlocked in a short cache and hands it back to the next login
 * under the same credentials, with no request made: that login does not raise the
 * demand, yet the account is as gated as it was. Clearing the flag on it released the
 * gate for good, and later launches signed in silently until one met the demand again.
 */
internal fun SessionData.provesNoSecondFactor(): Boolean = !twoFactor
