package hivens.accounts.api

import hivens.widget.model.CommandKey
import hivens.widget.model.SourceKey

/**
 * Every key the accounts module answers: the id as a constant, for `needs` and for
 * the document, and the typed key a widget reads or fires.
 *
 * All of them live at the app level, because the accounts are the app's: two windows
 * show one session. They exist only where a document names the accounts module, and
 * a widget that needs one stands misplaced anywhere else.
 *
 * Nothing here carries a secret. A session reads as a [SessionView], an account as an
 * [AccountView], and a sign-in hands its password to the module, which never hands
 * it back.
 *
 * Commands are fire-and-forget. What came of one is read from a source: a sign-in
 * from [SignIns] and [Session], a sign-out from [Session], a skin upload from
 * [SkinUploads].
 */
object AccountKeys {
    const val SMARTYCRAFT: String = "smartycraft"
    const val MICROSOFT: String = "microsoft"
    const val OFFLINE: String = "offline"

    const val SESSION_ID: String = "app.state"
    const val ACCOUNTS_ID: String = "accounts.list"
    const val PROVIDERS_ID: String = "accounts.providers"
    const val FACE_ID: String = "accounts.face"
    const val REMEMBER_ID: String = "accounts.remember"
    const val SIGN_INS_ID: String = "accounts.signIns"
    const val SECOND_FACTOR_ID: String = "accounts.secondFactor"
    const val SKIN_UPLOADS_ID: String = "accounts.skin.uploads"
    const val REMOVALS_ID: String = "accounts.removals"

    const val SIGN_IN_SMARTYCRAFT_ID: String = "auth.signIn.$SMARTYCRAFT"
    const val SIGN_IN_MICROSOFT_ID: String = "auth.signIn.$MICROSOFT"
    const val SIGN_IN_OFFLINE_ID: String = "auth.signIn.$OFFLINE"
    const val SIGN_IN_CODE_ID: String = "auth.signIn.code"
    const val CANCEL_SIGN_IN_ID: String = "auth.signIn.cancel"
    const val SIGN_OUT_ID: String = "auth.signOut"
    const val REMOVE_ACCOUNT_ID: String = "accounts.remove"
    const val SET_FACE_ID: String = "accounts.face.set"
    const val SET_REMEMBER_ID: String = "accounts.remember.set"
    const val ANSWER_SECOND_FACTOR_ID: String = "accounts.secondFactor.answer"
    const val DISMISS_SECOND_FACTOR_ID: String = "accounts.secondFactor.dismiss"
    const val UPLOAD_SKIN_ID: String = "accounts.skin.upload"

    /** Who the launcher is signed in as, that nobody is, or that startup is still finding out. */
    val Session: SourceKey<SessionState> = SourceKey(SESSION_ID)

    /**
     * Every stored account, in the order the store keeps them, or null until the store
     * has first answered, so a reader does not offer to sign in an account that is
     * signed in. An offline identity is not an account.
     */
    val Accounts: SourceKey<List<AccountView>?> = SourceKey(ACCOUNTS_ID)

    /** The providers a person can sign in with in this build, offline included. */
    val Providers: SourceKey<List<ProviderView>> = SourceKey(PROVIDERS_ID)

    /** Which signed-in provider fronts the launcher when more than one is. Changed through [SetFace]. */
    val Face: SourceKey<FaceChoice> = SourceKey(FACE_ID)

    /** Whether a sign-in keeps its account for the next start, as the person last chose. Changed through [SetRemember]. */
    val Remember: SourceKey<Boolean> = SourceKey(REMEMBER_ID)

    /** The sign-ins under way or failed, by provider key. A provider with none is absent. */
    val SignIns: SourceKey<Map<String, SignInFlow>> = SourceKey(SIGN_INS_ID)

    /**
     * A launch waiting on a second factor, or null when none is. Answered through
     * [AnswerSecondFactor], given up through [DismissSecondFactor].
     */
    val SecondFactor: SourceKey<SecondFactorQuestion?> = SourceKey(SECOND_FACTOR_ID)

    /** The latest skin upload to each account, by provider key. Started by [UploadSkin]. */
    val SkinUploads: SourceKey<Map<String, SkinUploadView>> = SourceKey(SKIN_UPLOADS_ID)

    /** The latest removal of each provider's account and how it ended, by provider key. Started by [RemoveAccount]. */
    val Removals: SourceKey<Map<String, RemovalView>> = SourceKey(REMOVALS_ID)

    /** Signs in with a SmartyCraft name and password. Its progress is in [SignIns]. */
    val SignInSmartyCraft: CommandKey<PasswordSignIn> = CommandKey(SIGN_IN_SMARTYCRAFT_ID)

    /**
     * Starts a Microsoft device-code sign-in. Its code and progress are in [SignIns].
     * Fails as `unavailable` in a build with no Microsoft client, which
     * [Providers] lists no Microsoft provider for.
     */
    val SignInMicrosoft: CommandKey<DeviceCodeSignIn> = CommandKey(SIGN_IN_MICROSOFT_ID)

    /** Signs in offline under this name, and keeps the name for the next start. */
    val SignInOffline: CommandKey<String> = CommandKey(SIGN_IN_OFFLINE_ID)

    /** Answers the second factor a sign-in in [SignIns] stopped for. */
    val SignInCode: CommandKey<SignInCode> = CommandKey(SIGN_IN_CODE_ID)

    /** Stops the sign-in with the provider of this key and forgets how it ended. */
    val CancelSignIn: CommandKey<String> = CommandKey(CANCEL_SIGN_IN_ID)

    /**
     * Signs out of everything: every stored account, the face choice and the
     * offline name. At once, so whatever offers it asks the person first.
     */
    val SignOut: CommandKey<Unit> = CommandKey(SIGN_OUT_ID)

    /**
     * Removes one provider's account, and moves the launcher to the face that is left.
     * For the last account whatever offers it asks for [SignOut] instead, and where
     * the store gives no face for the accounts left, the outcome in [Removals] says so
     * and the launcher stays as it was, for the offerer to ask the same question.
     */
    val RemoveAccount: CommandKey<AccountRemoval> = CommandKey(REMOVE_ACCOUNT_ID)

    /** Names the provider that fronts the launcher, and signs in as its account at once. */
    val SetFace: CommandKey<FaceChoice> = CommandKey(SET_FACE_ID)

    /** Says whether a sign-in keeps its account. Accounts already kept stay. */
    val SetRemember: CommandKey<Boolean> = CommandKey(SET_REMEMBER_ID)

    /** Answers the launch's second factor with this code. */
    val AnswerSecondFactor: CommandKey<String> = CommandKey(ANSWER_SECOND_FACTOR_ID)

    /** Gives the waiting launch up: it does not happen. */
    val DismissSecondFactor: CommandKey<Unit> = CommandKey(DISMISS_SECOND_FACTOR_ID)

    /** Puts a skin or a cape on an account. The outcome is in [SkinUploads]. */
    val UploadSkin: CommandKey<SkinUpload> = CommandKey(UPLOAD_SKIN_ID)
}
