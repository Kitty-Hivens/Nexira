package hivens.accounts.api

import hivens.widget.model.ApiVersion
import hivens.widget.model.CommandKey
import hivens.widget.model.ScopeLevel
import hivens.widget.model.SourceKey
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AccountsApiTest {

    @Test
    fun `the version module builds stamp is the version the API says it is`() {
        val stamped = requireNotNull(System.getProperty("nexira.accountsApi")) { "the build did not pass nexira.accountsApi" }
        assertEquals(AccountsApi.VERSION, ApiVersion.parse(stamped))
    }

    @Test
    fun `every typed key carries the id its constant names, at the app level`() {
        val pairs = listOf<Pair<String, Any>>(
            AccountKeys.SESSION_ID to AccountKeys.Session,
            AccountKeys.ACCOUNTS_ID to AccountKeys.Accounts,
            AccountKeys.PROVIDERS_ID to AccountKeys.Providers,
            AccountKeys.FACE_ID to AccountKeys.Face,
            AccountKeys.REMEMBER_ID to AccountKeys.Remember,
            AccountKeys.SIGN_INS_ID to AccountKeys.SignIns,
            AccountKeys.SECOND_FACTOR_ID to AccountKeys.SecondFactor,
            AccountKeys.SKIN_UPLOADS_ID to AccountKeys.SkinUploads,
            AccountKeys.REMOVALS_ID to AccountKeys.Removals,
            AccountKeys.SIGN_IN_SMARTYCRAFT_ID to AccountKeys.SignInSmartyCraft,
            AccountKeys.SIGN_IN_MICROSOFT_ID to AccountKeys.SignInMicrosoft,
            AccountKeys.SIGN_IN_OFFLINE_ID to AccountKeys.SignInOffline,
            AccountKeys.SIGN_IN_CODE_ID to AccountKeys.SignInCode,
            AccountKeys.CANCEL_SIGN_IN_ID to AccountKeys.CancelSignIn,
            AccountKeys.SIGN_OUT_ID to AccountKeys.SignOut,
            AccountKeys.REMOVE_ACCOUNT_ID to AccountKeys.RemoveAccount,
            AccountKeys.SET_FACE_ID to AccountKeys.SetFace,
            AccountKeys.SET_REMEMBER_ID to AccountKeys.SetRemember,
            AccountKeys.ANSWER_SECOND_FACTOR_ID to AccountKeys.AnswerSecondFactor,
            AccountKeys.DISMISS_SECOND_FACTOR_ID to AccountKeys.DismissSecondFactor,
            AccountKeys.UPLOAD_SKIN_ID to AccountKeys.UploadSkin,
        )
        pairs.forEach { (id, key) ->
            val (keyId, level) = when (key) {
                is SourceKey<*> -> key.id to key.level
                is CommandKey<*> -> key.id to key.level
                else -> error("not a key: $key")
            }
            assertEquals(id, keyId)
            assertEquals(ScopeLevel.App, level, id)
        }
        assertEquals(pairs.size, pairs.map { it.first }.toSet().size, "two keys share an id")
    }

    @Test
    fun `the ids the launcher shipped before the module existed are kept`() {
        // The built-in widgets name these in their needs, so a rename would leave those
        // widgets misplaced with nothing failing at build time.
        val shipped = setOf("app.state", "auth.signOut")
        assertTrue(setOf(AccountKeys.SESSION_ID, AccountKeys.SIGN_OUT_ID).containsAll(shipped))
    }

    /** The instance fields of [type], by name. */
    private fun fieldsOf(type: Class<*>): Set<String> =
        type.declaredFields.filterNot { Modifier.isStatic(it.modifiers) }.map { it.name }.toSet()

    // Held field by field: a token or a password added to either would reach every
    // module and every widget that reads the session or the accounts.
    @Test
    fun `what a widget reads of a session or an account carries no secret`() {
        assertEquals(setOf("name", "uuid", "providerKey", "offline"), fieldsOf(SessionView::class.java))
        assertEquals(
            setOf("providerKey", "accountId", "name", "uuid", "balance", "clan", "clanResolved", "online"),
            fieldsOf(AccountView::class.java),
        )
    }

    @Test
    fun `a password sign-in written out leaves the password out`() {
        val written = PasswordSignIn("Steve", "hunter2", remember = true).toString()
        assertTrue("hunter2" !in written, written)
        assertTrue("Steve" in written, written)
    }

    @Test
    fun `a sign-in per provider is named after the provider`() {
        assertEquals("auth.signIn.smartycraft", AccountKeys.SIGN_IN_SMARTYCRAFT_ID)
        assertEquals("auth.signIn.microsoft", AccountKeys.SIGN_IN_MICROSOFT_ID)
        assertEquals("auth.signIn.offline", AccountKeys.SIGN_IN_OFFLINE_ID)
    }
}
