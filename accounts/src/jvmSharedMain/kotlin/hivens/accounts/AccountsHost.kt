package hivens.accounts

import hivens.core.data.SessionData
import hivens.widget.api.HostApi
import org.slf4j.LoggerFactory

/**
 * What the accounts module needs from the application around it and does not own: a
 * place to say out loud what could not be done, and the writer the application saves
 * the person's choices through.
 *
 * The host provides one as a service. Where none is provided, the module only logs.
 */
interface AccountsHost {
    /** Tells the person that [trouble] happened, for [cause]. Every attempt that fails says so again. */
    fun failed(trouble: AccountTrouble, cause: Throwable)

    /** Takes back what was said about [trouble], once an attempt worked. */
    fun cleared(trouble: AccountTrouble)

    /** Saves a choice the person made, [what] naming it for the log, the way the host saves its own. */
    fun persist(what: String, write: () -> Unit)

    /**
     * A sign-in by hand was refused by a certificate the launcher does not trust.
     * Asks the person whether to trust it, the host's question since every read of a
     * server can meet it, and runs [retry] once they do. False where the host asks
     * nothing, and the sign-in then fails as any refusal does.
     */
    fun certificateRefused(retry: () -> Unit): Boolean
}

/** What can go wrong with the session that the person is told about. */
enum class AccountTrouble {
    /** The startup sign-in threw, and the launcher stays signed out. */
    SignIn,

    /** The stored accounts could not be cleared, so the launcher stays signed in. */
    SignOut,

    /** Signed out, but the face choice and the offline name are still on disk. */
    NameKept,
}

/** An [AccountsHost] for a host that provides none: everything goes to the log, and choices are saved at once. */
internal object LoggingAccountsHost : AccountsHost {
    private val log = LoggerFactory.getLogger(LoggingAccountsHost::class.java)
    override fun failed(trouble: AccountTrouble, cause: Throwable) = log.error("Accounts: {} failed", trouble, cause)
    override fun cleared(trouble: AccountTrouble) = Unit
    override fun persist(what: String, write: () -> Unit) {
        try {
            write()
        } catch (e: Exception) {
            log.warn("Could not save {}", what, e)
        }
    }
    override fun certificateRefused(retry: () -> Unit): Boolean = false
}

/** Who a launch runs as. */
sealed interface LaunchIdentity {
    /** The startup sign-in is still finding out. A launch now is refused rather than run as the wrong person. */
    data object Resolving : LaunchIdentity

    /** Nobody is signed in, so a launch runs as the offline name, when there is one. */
    data object Nobody : LaunchIdentity

    /** Signed in as [session], tokens and all. */
    data class SignedIn(val session: SessionData) : LaunchIdentity
}

/**
 * Who a launch started now runs as, read at the moment it starts.
 *
 * It carries the session with its tokens, so it is a host service of the accounts
 * module: the host and the modules compiled into it reach it through a level's host
 * view, and a module loaded from a jar is answered as if it did not exist.
 */
@HostApi
interface LaunchSessions {
    val identity: LaunchIdentity
}
