package hivens.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import hivens.accounts.api.AccountKeys
import hivens.accounts.api.PasswordSignIn
import hivens.accounts.api.ProviderView
import hivens.accounts.api.SignInFlow
import hivens.themes.Themes
import hivens.ui.i18n.ShippedLanguageProvider
import hivens.ui.layers.LayerHost
import hivens.ui.layers.rememberWindowLayers
import hivens.ui.theme.NxThemed
import hivens.ui.widgets.ChildLevel
import hivens.ui.widgets.KoinLevelServicesHost
import hivens.widget.api.LocalScope
import hivens.widget.api.LocalWidgetCommandRegistry
import hivens.widget.api.LocalWidgetDataRegistry
import hivens.widget.api.WidgetCommandRegistry
import hivens.widget.api.WidgetDataRegistry
import hivens.widget.api.WidgetScope
import hivens.widget.api.command
import hivens.widget.api.flowSource
import hivens.widget.model.ScopeLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The sign-in form's password field typed into: the letters stay masked, every one of
 * them, and Enter signs in with the whole password.
 */
class LoginPasswordTypingTest {

    private val signIns = CopyOnWriteArrayList<PasswordSignIn>()

    private val data = WidgetDataRegistry().apply {
        register(AccountKeys.SignIns, flowSource(MutableStateFlow<Map<String, SignInFlow>>(emptyMap())))
        register(AccountKeys.Providers, flowSource(MutableStateFlow(listOf(ProviderView(AccountKeys.SMARTYCRAFT, "SmartyCraft", ProviderView.PASSWORD)))))
        register(AccountKeys.Remember, flowSource(MutableStateFlow(false)))
    }

    private val commands = WidgetCommandRegistry().apply {
        register(AccountKeys.SignInSmartyCraft, command { request -> signIns += request })
        register(AccountKeys.SignInOffline, command { })
        register(AccountKeys.SignInCode, command { })
        register(AccountKeys.CancelSignIn, command { })
        register(AccountKeys.SetRemember, command { })
    }

    private val app: WidgetScope = koinApplication {
        modules(
            levelServicesModule,
            module { single { WidgetScope.app(data, KoinLevelServicesHost.app(this), Dispatchers.Unconfined, commands) } },
        )
    }.koin.get()

    private fun form(body: TypingScene.() -> Unit) = onEventThread {
        TypingScene(500, 700) {
            ShippedLanguageProvider("en") {
                NxThemed(Themes.default, dark = true) {
                    LayerHost(rememberWindowLayers(), Modifier.fillMaxSize()) {
                        CompositionLocalProvider(
                            LocalWidgetDataRegistry provides data,
                            LocalWidgetCommandRegistry provides commands,
                            LocalScope provides app,
                        ) {
                            ChildLevel(ScopeLevel.Window) {
                                ChildLevel(ScopeLevel.Composition) {
                                    LoginPanel(showOffline = false, showMicrosoft = false)
                                }
                            }
                        }
                    }
                }
            }
        }.use { scene ->
            scene.settleUntil("the form") { scene.fields().size == 2 }
            scene.body()
        }
    }

    @Test
    fun `a letter typed into the password is masked from the first frame`() = form {
        focusField(PASSWORD)
        commit("W")
        settle(2)
        assertEquals("•", drawnText(PASSWORD), "the letter just typed is drawn masked too")
        commit("x")
        settle(2)
        assertEquals("••", drawnText(PASSWORD))
    }

    @Test
    fun `Enter right after the last letter signs in with the whole password`() = form {
        focusField(NAME)
        commit("Steve")
        settle()
        focusField(PASSWORD)
        "hunter2".forEach { commit(it.toString()) }
        press(Key.Enter)
        settle()
        assertEquals(listOf(PasswordSignIn("Steve", "hunter2", remember = false)), signIns.toList())
    }

    @Test
    fun `a password from an input method is signed in with whole`() = form {
        focusField(NAME)
        commit("Steve")
        settle()
        focusField(PASSWORD)
        compose("пар")
        settle()
        assertTrue(composing)
        commit("пароль")
        press(Key.Enter)
        settle()
        assertEquals(listOf("пароль"), signIns.map { it.password })
    }

    private companion object {
        const val NAME = 0
        const val PASSWORD = 1
    }

    // The secure field's own default differs between framework versions, and the newer one
    // hides every letter on a desktop and still shows the last on Android when the system
    // says so. A desktop run cannot see that difference, so the call itself is read.
    @Test
    fun `the password field is set to keep every letter hidden on every platform`() {
        val source = File("src/jvmSharedMain/kotlin/hivens/ui/LoginPanel.kt")
        assertTrue(source.isFile, "this test reads the sign-in form's source at ${source.absolutePath}")
        val call = source.readText().substringAfter("OutlinedSecureTextField(").substringBefore("\n        )")
        assertTrue(
            Regex("""textObfuscationMode\s*=\s*TextObfuscationMode\.Hidden\b""").containsMatchIn(call),
            "the password field leaves its masking to the framework's default",
        )
    }

    /**
     * The password field draws [expected] masked at the next frame, and Enter right then signs
     * in with it: the form holds what the field holds. The name is left empty, so the sign in
     * keeps the focus in the field.
     */
    private fun TypingScene.signsInWith(expected: String, what: String) {
        settle(1)
        assertEquals("•".repeat(expected.length), drawnText(PASSWORD), "the field after $what")
        press(Key.Enter)
        settle()
        assertEquals(expected, signIns.last().password, "the password signed in with after $what")
    }

    @Test
    fun `Ctrl and C and Ctrl and X copy nothing out of the password`() = form {
        focusField(PASSWORD)
        clipboard.text = "from before"
        commit("hunter2")
        settle()
        press(Key.A, shortcut = true)
        settle(1)
        assertEquals("hunter2".length, selection.length, "Ctrl and A selected the whole password")
        press(Key.C, shortcut = true)
        settleAWhile()
        assertEquals("from before", clipboard.text, "a copy of the password reached the clipboard")
        press(Key.X, shortcut = true)
        settleAWhile()
        assertEquals("from before", clipboard.text, "a cut of the password reached the clipboard")
        signsInWith("hunter2", "a refused copy and cut")
    }

    @Test
    fun `a password pasted with Ctrl and V is the one signed in with`() = form {
        focusField(PASSWORD)
        clipboard.text = "hunter2"
        paste()
        signsInWith("hunter2", "a paste")
    }

    @Test
    fun `a paste over a selected password replaces it`() = form {
        focusField(PASSWORD)
        clipboard.text = "hunter2"
        commit("wrong")
        settle()
        press(Key.A, shortcut = true)
        paste()
        signsInWith("hunter2", "a paste over the password")
    }

    @Test
    fun `typing over a selected password replaces it`() = form {
        focusField(PASSWORD)
        commit("wrong")
        settle()
        press(Key.A, shortcut = true)
        commit("right")
        signsInWith("right", "typing over the password")
    }
}
