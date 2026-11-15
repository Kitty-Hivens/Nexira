package hivens.ui.accounts

import hivens.accounts.AccountTrouble
import hivens.accounts.AccountsHost
import hivens.launcher.network.CertificateTrustGate
import hivens.ui.notifications.CoreProblem
import hivens.ui.notifications.CoreProblems
import hivens.ui.utils.PreferenceWriter

/**
 * What the accounts module asks of the launcher around it, over the launcher's own
 * parts: the core problems say a failed sign-in or sign-out in the person's words, the
 * preference writer saves a choice the way every other click is saved, and the
 * certificate gate asks about [certificateHost], the launcher's own server, whose
 * question the shell draws for every read that meets it.
 */
class ClientAccountsHost(
    private val problems: CoreProblems,
    private val preferences: PreferenceWriter,
    private val certificates: CertificateTrustGate,
    private val certificateHost: String,
) : AccountsHost {

    override fun failed(trouble: AccountTrouble, cause: Throwable) = problems.report(
        when (trouble) {
            AccountTrouble.SignIn -> CoreProblem.SignInFailed(cause)
            AccountTrouble.SignOut -> CoreProblem.SignOutFailed(cause)
            AccountTrouble.NameKept -> CoreProblem.SignedOutNameKept(cause)
        },
    )

    override fun cleared(trouble: AccountTrouble) = problems.withdraw(
        when (trouble) {
            AccountTrouble.SignIn -> CoreProblem.SignInFailed::class
            AccountTrouble.SignOut -> CoreProblem.SignOutFailed::class
            AccountTrouble.NameKept -> CoreProblem.SignedOutNameKept::class
        },
    )

    override fun persist(what: String, write: () -> Unit) = preferences.write(what, write)

    // The gate records the grant before it runs the retry, so the same provider then
    // reaches the host over the channel the person trusted.
    override fun certificateRefused(retry: () -> Unit): Boolean {
        certificates.request(certificateHost, retry)
        return true
    }
}
