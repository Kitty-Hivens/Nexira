package hivens.accounts.api

/**
 * Who the launcher is signed in as, as a widget reads it. Plain data and nothing
 * secret: no token, no password, nothing a request could be signed with.
 */
data class SessionView(
    /** The player's name, as the game shows it. */
    val name: String,
    /** The player's uuid without dashes, or empty where the provider gave none. */
    val uuid: String,
    /** The provider the session came from, `smartycraft`, `microsoft` or `offline` among them. */
    val providerKey: String,
    /** Whether it is an offline identity, a name with no provider behind it. */
    val offline: Boolean,
)

/** Where the launcher's session stands. */
sealed interface SessionState {
    /** The startup sign-in is still finding out who the launcher is. */
    data object Resolving : SessionState

    /** Nobody is signed in. */
    data object SignedOut : SessionState

    data class SignedIn(val session: SessionView) : SessionState
}

/** One stored account, as a widget reads it. Nothing secret, as [SessionView]. */
data class AccountView(
    val providerKey: String,
    /** The account's id within its provider: its uuid, or its name where it has none. */
    val accountId: String,
    val name: String,
    val uuid: String,
    /** The balance the provider last reported, 0 where it keeps none. */
    val balance: Int = 0,
    /** The clan tag the provider last reported, null for none or not known. */
    val clan: String? = null,
    /** Whether [clan] is known: false for an account stored before the provider said. */
    val clanResolved: Boolean = false,
    /** Whether the account holds a session its provider issued, rather than a name alone. */
    val online: Boolean = false,
)

/**
 * A provider a person can sign in with. [kind] says how: `password` takes a name and
 * a password, `device-code` hands out a code to enter elsewhere, `offline` takes a
 * name alone. A reader treats a kind it does not know as one it cannot offer.
 */
data class ProviderView(
    val key: String,
    /** The provider's own name, the same in every language. */
    val name: String,
    val kind: String,
    /** Whether a sign-in may stop for a second factor, which [AccountKeys.SignInCode] answers. */
    val secondFactor: Boolean = false,
    /** Where a person makes an account with the provider, null where there is no such page. */
    val registerUrl: String? = null,
) {
    companion object {
        const val PASSWORD: String = "password"
        const val DEVICE_CODE: String = "device-code"
        const val OFFLINE: String = "offline"
    }
}

/** Which signed-in provider fronts the launcher. Null [providerKey] leaves it to the launcher's order. */
data class FaceChoice(val providerKey: String? = null)

/** Where one provider's sign-in stands. Absent from [AccountKeys.SignIns] when nothing is under way. */
sealed interface SignInFlow {
    /** Asking the provider. */
    data object Working : SignInFlow

    /**
     * The provider handed out [userCode] to enter at [verificationUri], and the
     * launcher waits for it. [error] is what the wait last failed with, null while
     * it goes on, and a reader shows its own words where the message is null.
     */
    data class DeviceCode(val userCode: String, val verificationUri: String, val error: SignInError? = null) : SignInFlow

    /**
     * The provider asks for a second factor, answered with [AccountKeys.SignInCode].
     * [checking] while a code is with the provider, [error] when the last one failed.
     */
    data class SecondFactor(val checking: Boolean = false, val error: SignInError? = null) : SignInFlow

    /** Not signed in, for [error]. Stays until the next sign-in or [AccountKeys.CancelSignIn]. */
    data class Failed(val error: SignInError) : SignInFlow
}

/**
 * Why a sign-in step failed. [code] says it in a word a widget can choose its own
 * text by: `empty` for a name or a password left blank, `rejected` when the provider
 * said no, `wrong-code` for a second factor it did not take, `second-factor-expired`
 * when the provider let the sign-in lapse, `second-factor-unsupported` when it asked
 * for a second factor the launcher cannot answer there, `unavailable` for a provider
 * this build does not carry, `failed` for anything else, or another a newer build
 * adds. [message] is the provider's own words where there are any.
 */
data class SignInError(val code: String, val message: String? = null) {
    companion object {
        const val EMPTY: String = "empty"
        const val REJECTED: String = "rejected"
        const val WRONG_CODE: String = "wrong-code"
        const val SECOND_FACTOR_EXPIRED: String = "second-factor-expired"
        const val SECOND_FACTOR_UNSUPPORTED: String = "second-factor-unsupported"
        const val UNAVAILABLE: String = "unavailable"
        const val FAILED: String = "failed"
    }
}

/**
 * A sign-in with a name and a password. [remember] keeps the account for the next
 * start. Its text form leaves the password out, so a log line never carries it.
 */
data class PasswordSignIn(val login: String, val password: String, val remember: Boolean) {
    override fun toString(): String = "PasswordSignIn(login=$login, password=<hidden>, remember=$remember)"
}

/**
 * The account of [providerKey] to remove. [ticket] is the caller's own, handed back
 * in [RemovalView] so it can tell its removal's outcome from another's.
 */
data class AccountRemoval(val providerKey: String, val ticket: String = "")

/** The latest removal of one provider's account and how it ended. */
data class RemovalView(val ticket: String, val outcome: RemovalOutcome)

/** How a removal ended. */
sealed interface RemovalOutcome {
    data object Removing : RemovalOutcome

    /** Removed, and the launcher now fronts the account that was left. */
    data object Moved : RemovalOutcome

    /**
     * Removed, and no account is left that could front the launcher, which stays
     * signed in as it was. Whatever asked for the removal asks the person whether to
     * sign out of everything, the way it would for the last account.
     */
    data object NoFaceLeft : RemovalOutcome

    /** Not removed. [message] is the failure's own words, null where there were none. */
    data class Failed(val message: String? = null) : RemovalOutcome
}

/** A device-code sign-in. [remember] keeps the account for the next start. */
data class DeviceCodeSignIn(val remember: Boolean = true)

/** The second factor of the sign-in under way with [providerKey]. */
data class SignInCode(val providerKey: String, val code: String)

/**
 * A launch waiting on a second factor, asked of the person for the account of
 * [providerKey]. [label] is what is being launched. [checking] while a code is with
 * the provider, [error] the provider's words when the last one failed.
 */
data class SecondFactorQuestion(
    val label: String,
    val providerKey: String,
    val checking: Boolean = false,
    val error: String? = null,
)

/**
 * A skin, or a cape with [cape], to put on the account of [providerKey], from the
 * PNG at [path]. [ticket] is the caller's own, handed back in [SkinUploadView] so it
 * can tell its upload's outcome from another's.
 *
 * With [asSignedIn] it is signed with the session the launcher is signed in as,
 * which [providerKey] then names the provider of, rather than with the stored
 * account: a sign-in the person did not keep has no stored account to sign with.
 */
data class SkinUpload(
    val providerKey: String,
    val path: String,
    val cape: Boolean = false,
    val ticket: String = "",
    val asSignedIn: Boolean = false,
)

/** The latest upload to one account and where it stands. */
data class SkinUploadView(val ticket: String, val status: SkinUploadStatus)

/** Where an upload stands. */
sealed interface SkinUploadStatus {
    data object Uploading : SkinUploadStatus

    data object Done : SkinUploadStatus

    /** Not taken. [message] is the provider's answer or the failure's own words, null where there were none. */
    data class Failed(val message: String? = null) : SkinUploadStatus
}
