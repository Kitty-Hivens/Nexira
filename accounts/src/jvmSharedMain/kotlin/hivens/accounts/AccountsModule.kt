// The accounts module is compiled into the application and trusted with the host's own
// services, which it reads through the host view of each level, and it keeps the
// session for the host and the modules the host trusts.
@file:OptIn(HostApi::class)

package hivens.accounts

import hivens.accounts.api.AccountKeys
import hivens.accounts.api.AccountsApi
import hivens.accounts.api.FaceChoice
import hivens.accounts.api.ProviderView
import hivens.accounts.api.SessionState
import hivens.accounts.api.SessionView
import hivens.auth.AccountStore
import hivens.auth.AuthProvider
import hivens.auth.AuthProviderRegistry
import hivens.auth.OfflineAuthProvider
import hivens.auth.RefreshableAuthProvider
import hivens.core.api.SkinRepository
import hivens.core.api.interfaces.ISettingsService
import hivens.core.security.SslBypassStore
import hivens.widget.api.CompiledModule
import hivens.widget.api.HostApi
import hivens.widget.api.KeyContribution
import hivens.widget.api.LevelServices
import hivens.widget.api.ModuleContribution
import hivens.widget.api.ModuleKeys
import hivens.widget.api.ModuleStrings
import hivens.widget.api.command
import hivens.widget.api.find
import hivens.widget.api.flowSource
import hivens.widget.api.get
import hivens.widget.api.hostService
import hivens.widget.api.hostServices
import hivens.widget.model.ApiVersion
import hivens.widget.model.ScopeLevel
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The accounts and sign-in module as the application carries it: compiled in, and
 * brought in by a document that names [AccountsApi.ID] like any jar would be.
 */
object AccountsModule : CompiledModule {
    override val id: String = AccountsApi.ID
    override val name: String = NAME
    override val strings: ModuleStrings = ModuleStrings.resources(AccountsModule::class.java, AccountsApi.ID)
    override val provides: Map<String, ApiVersion> = mapOf(AccountsApi.ID to AccountsApi.VERSION)

    // The API's own object lives in the API artifact's package, so the package is read off it.
    override val apiPackages: Map<String, Set<String>> = mapOf(AccountsApi.ID to setOf(AccountsApi::class.java.name.substringBeforeLast('.')))
    override val contributions: List<ModuleContribution> = listOf(AccountKeysContribution)

    private const val NAME = "accounts.name"
}

/**
 * Every key in [AccountKeys], answered over the launcher's credential store and its
 * sign-in providers, and the services behind them, one each for the app.
 *
 * Every service here is kept for the host and the modules it trusts: the session with
 * its tokens ([SessionStore], and [LaunchSessions] over it) and the rest alike, since
 * each of them stands on the credential store. A widget and a jar module reach the
 * accounts through the keys alone, which carry no secret.
 *
 * The engine's services and [AccountsHost] are the host's, read through the level's
 * host services, which the host opens to the modules compiled into it.
 */
object AccountKeysContribution : KeyContribution {
    override val id: String = "accounts.keys"
    override val label: String = "Accounts"

    /** What the palette shows a session as. */
    private val sample = SessionView(name = "Steve", uuid = "", providerKey = AccountKeys.OFFLINE, offline = true)

    override fun declare(keys: ModuleKeys) {
        keys.hostService(ScopeLevel.App) { context ->
            val services = context.hostServices
            AccountDirectory(
                accounts = services.get<AccountStore>(),
                settings = services.get<ISettingsService>(),
                host     = services.accountsHost(),
                scope    = context.coroutineScope,
            )
        }
        // Made the first time something reads the session, which starts the startup
        // sign-in, and closed with the app's services, which stops it.
        keys.hostService(ScopeLevel.App) { context ->
            val services = context.hostServices
            SessionStore(
                accounts          = services.get<AccountStore>(),
                settings          = services.get<ISettingsService>(),
                auth              = services.get<AuthProvider>(),
                // Present only where the build carries a provider that refreshes its
                // sessions, so the startup sign-in refreshes such an account exactly then.
                refreshable       = services.get<AuthProviderRegistry>().all.filterIsInstance<RefreshableAuthProvider>().firstOrNull(),
                certificateGrants = services.get<SslBypassStore>().bypasses,
                host              = services.accountsHost(),
                parent            = context.coroutineScope,
                accountsChanged   = services.get<AccountDirectory>()::refresh,
            )
        }
        keys.hostService<LaunchSessions>(ScopeLevel.App) { context -> context.hostServices.get<SessionStore>() }
        keys.hostService(ScopeLevel.App) { context ->
            val services = context.hostServices
            SignIns(
                registry     = services.get<AuthProviderRegistry>(),
                password     = services.get<AuthProvider>(),
                offline      = services.get<OfflineAuthProvider>(),
                accounts     = services.get<AccountStore>(),
                settings     = services.get<ISettingsService>(),
                host         = services.accountsHost(),
                session      = services.get<SessionStore>(),
                directory    = services.get<AccountDirectory>(),
                scope        = context.coroutineScope,
            )
        }
        keys.hostService(ScopeLevel.App) { context ->
            val services = context.hostServices
            LaunchSecondFactor(
                // A host that runs no launches has no gate, and nothing is ever asked.
                gate            = services.find<TwoFactorLaunchGate>(),
                accounts        = services.get<AccountStore>(),
                registry        = services.get<AuthProviderRegistry>(),
                auth            = services.get<AuthProvider>(),
                scope           = context.coroutineScope,
                accountsChanged = services.get<AccountDirectory>()::refresh,
            )
        }
        keys.hostService(ScopeLevel.App) { context ->
            val services = context.hostServices
            SkinUploads(
                send     = { file, cape, session -> services.get<SkinRepository>().uploadSkin(file, cape, session) },
                accounts = services.get<AccountStore>(),
                signedIn = { services.get<SessionStore>().session },
                scope    = context.coroutineScope,
            )
        }

        keys.source(AccountKeys.Session, sample = { SessionState.SignedIn(sample) }) { app ->
            flowSource(app.hostServices.get<SessionStore>().state)
        }
        keys.source(AccountKeys.Accounts, sample = { emptyList() }) { app -> flowSource(app.hostServices.get<AccountDirectory>().list) }
        keys.source(AccountKeys.Providers, sample = { emptyList() }) { app -> flowSource(MutableStateFlow(providersOf(app.hostServices))) }
        keys.source(AccountKeys.Face, sample = { FaceChoice() }) { app -> flowSource(app.hostServices.get<AccountDirectory>().face) }
        keys.source(AccountKeys.Remember, sample = { true }) { app -> flowSource(app.hostServices.get<AccountDirectory>().remember) }
        keys.source(AccountKeys.SignIns, sample = { emptyMap() }) { app -> flowSource(app.hostServices.get<SignIns>().flows) }
        keys.source(AccountKeys.SecondFactor, sample = { null }) { app -> flowSource(app.hostServices.get<LaunchSecondFactor>().question) }
        keys.source(AccountKeys.SkinUploads, sample = { emptyMap() }) { app -> flowSource(app.hostServices.get<SkinUploads>().uploads) }
        keys.source(AccountKeys.Removals, sample = { emptyMap() }) { app -> flowSource(app.hostServices.get<AccountDirectory>().removals) }

        // The per-provider sign-in keys are declared by their ids, since a declaration
        // runs before the provider registry can be read. Each goes to the provider of
        // its key, found in the registry, and fails as unavailable where there is none.
        keys.command(AccountKeys.SignInSmartyCraft) { app ->
            val signIns = app.hostServices.get<SignIns>()
            command { request -> signIns.password(AccountKeys.SMARTYCRAFT, request) }
        }
        keys.command(AccountKeys.SignInMicrosoft) { app ->
            val signIns = app.hostServices.get<SignIns>()
            command { request -> signIns.deviceCode(request, AccountKeys.MICROSOFT) }
        }
        keys.command(AccountKeys.SignInOffline) { app -> command(app.hostServices.get<SignIns>()::offline) }
        keys.command(AccountKeys.SignInCode) { app -> command(app.hostServices.get<SignIns>()::code) }
        keys.command(AccountKeys.CancelSignIn) { app -> command(app.hostServices.get<SignIns>()::cancel) }
        keys.command(AccountKeys.SignOut) { app ->
            val store = app.hostServices.get<SessionStore>()
            command { val _ = store.signOut() }
        }
        keys.command(AccountKeys.RemoveAccount) { app ->
            val directory = app.hostServices.get<AccountDirectory>()
            val store = app.hostServices.get<SessionStore>()
            command { request -> directory.remove(request, store) }
        }
        keys.command(AccountKeys.SetFace) { app ->
            val directory = app.hostServices.get<AccountDirectory>()
            val store = app.hostServices.get<SessionStore>()
            command { choice -> directory.setFace(choice, store) }
        }
        keys.command(AccountKeys.SetRemember) { app -> command(app.hostServices.get<AccountDirectory>()::setRemember) }
        keys.command(AccountKeys.AnswerSecondFactor) { app -> command(app.hostServices.get<LaunchSecondFactor>()::answer) }
        keys.command(AccountKeys.DismissSecondFactor) { app ->
            val asking = app.hostServices.get<LaunchSecondFactor>()
            command { asking.dismiss() }
        }
        keys.command(AccountKeys.UploadSkin) { app -> command(app.hostServices.get<SkinUploads>()::upload) }
    }
}

/** The host's [AccountsHost], or one that only logs where the host provides none. */
private fun LevelServices.accountsHost(): AccountsHost = find<AccountsHost>() ?: LoggingAccountsHost

/**
 * The providers this build signs in with: the one that takes a password, every one
 * the registry carries, by what each can do, and offline. Fixed for the run, since
 * the registry is.
 */
internal fun providersOf(services: LevelServices): List<ProviderView> {
    val password = services.find<AuthProvider>()
    val registered = services.find<AuthProviderRegistry>()?.all.orEmpty()
    val online = (listOfNotNull(password) + registered)
        .distinctBy { it.id }
        .filter { it.id != OfflineAuthProvider.PROVIDER_KEY }
        .map { provider ->
            ProviderView(
                key          = provider.id,
                name         = provider.displayName,
                kind         = if (provider.capabilities.supportsDeviceCode) ProviderView.DEVICE_CODE else ProviderView.PASSWORD,
                secondFactor = provider.capabilities.supports2FA,
                registerUrl  = provider.registerUrl,
            )
        }
    return online + ProviderView(OfflineAuthProvider.PROVIDER_KEY, "Offline", ProviderView.OFFLINE)
}
