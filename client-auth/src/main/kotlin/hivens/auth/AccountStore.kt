package hivens.auth

import hivens.core.api.interfaces.ICredentialStore
import hivens.core.data.SessionData

/** Non-secret summary of a stored account, for the account-manager UI. */
data class StoredAccount(
    val providerId: String,
    val accountId: String,
    val username: String,
    val uuid: String,
    val displayName: String,
)

/**
 * The account-management surface over the credential store: everything the
 * account-manager UI composes on top of [ICredentialStore]'s read slice
 * (the active session + the per-provider lookup the launch flow uses).
 * One concrete store implements it ([CredentialsManager]); consumers inject
 * this contract, never the concretion.
 */
interface AccountStore : ICredentialStore {

    /**
     * Persist [session] as the [providerId] account and make it active. No-op when
     * the accessToken is blank (an offline identity carries nothing to store).
     */
    fun saveAccount(session: SessionData, providerId: String)

    /**
     * Records that [providerId]'s account completed a sign-in without being asked
     * for a second factor, releasing the gate [saveAccount] otherwise keeps set.
     *
     * The gate is deliberately sticky across ordinary saves: a re-login that simply
     * arrives without the flag must not clear what a real second factor established.
     * That leaves no way back for an account whose provider later turns 2FA off, and
     * a stuck gate is not cosmetic -- it fails every launch with TwoFactorExpired and
     * throws on every background sync pass. So the release is a separate, explicit
     * call, made only where the launcher has evidence rather than an absence: a login
     * that returned a session instead of a 2FA challenge.
     */
    fun clearTwoFactor(providerId: String)

    /** Active-account shim for [ICredentialStore] writers; infers the provider from the session shape. */
    fun save(session: SessionData)

    fun listAccounts(): List<StoredAccount>

    fun activeAccountId(): String?

    /**
     * The session that should front the shell -- the "primary face". When
     * [preferredProviderId] names a provider with a signed-in account, that
     * account wins; otherwise it falls back to licence priority (Microsoft, the
     * licensed account, before SmartyCraft; unknown providers last) rather than
     * to whichever account was saved last. Callers recompute it on add/remove so
     * the face follows the choice, or the highest-priority account when the
     * chosen provider has none. An offline identity is not an account, so it
     * never wins here -- it is reconstructed separately from
     * `SettingsData.offlinePlayerName`.
     */
    fun primarySession(preferredProviderId: String? = null): SessionData?

    fun loadSession(accountId: String): SessionData?

    fun setActive(accountId: String)

    fun removeAccount(accountId: String)

    /** Wipe every account's secrets and the file. */
    fun clear()
}
