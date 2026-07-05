package hivens.launcher.di

import hivens.config.Protocol
import hivens.config.Storage
import hivens.auth.AccountStore
import hivens.auth.AuthProvider
import hivens.auth.CredentialsManager
import hivens.auth.LegacyCredentialsManager
import hivens.auth.AuthProviderRegistry
import hivens.auth.OfflineAuthProvider
import hivens.auth.microsoft.MsaAuthProvider
import hivens.auth.smartycraft.SmartyCraftAuthProvider
import hivens.launcher.network.ChannelRouter
import hivens.launcher.network.NetworkState
import hivens.launcher.network.MsaConfig
import hivens.launcher.network.MsaConfigLoader
import hivens.launcher.network.ServerProtocolConfig
import hivens.launcher.network.ServerProtocolConfigLoader
import hivens.launcher.protocol.LauncherHashCache
import hivens.launcher.protocol.SmartycraftV1Protocol
import hivens.core.api.HttpClientProvider
import hivens.core.api.PlayerRepository
import hivens.core.api.ServerRepository
import hivens.core.api.SkinRepository
import hivens.core.api.interfaces.*
import dev.hivens.libvault.SecretVault
import dev.hivens.libvault.Vault
import dev.hivens.libvault.VaultConfig
import dev.hivens.libvault.VaultTier
import hivens.launcher.*
import hivens.launcher.component.ClasspathProvider
import hivens.launcher.component.EnvironmentPreparer
import hivens.launcher.component.GameCommandBuilder
import hivens.launcher.component.ProcessLogHandler
import hivens.launcher.launch.LauncherController
import hivens.launcher.mrpack.MrpackInstaller
import hivens.launcher.AgentExtractor
import hivens.launcher.ProfilerProfileStore
import hivens.launcher.platform.PlatformPaths
import hivens.launcher.runtime.RuntimeProvisioner
import hivens.launcher.runtime.loader.FabricLikeResolver
import hivens.launcher.runtime.loader.ForgeLegacyResolver
import hivens.launcher.runtime.loader.ForgeResolver
import hivens.launcher.runtime.loader.LoaderRegistry
import hivens.launcher.runtime.loader.ModernInstallerResolver
import hivens.launcher.security.KeyringStorageFactory
import hivens.core.smrt.ModIconResolver
import hivens.core.api.dto.modrinth.ModrinthProject
import hivens.core.api.dto.modrinth.ModrinthVersion
import hivens.core.api.dto.smrt.SmrtPackListing
import hivens.core.api.dto.smrt.SmrtPackManifest
import hivens.core.api.dto.smrt.SmrtPackSummary
import hivens.core.cache.CacheConfig
import hivens.core.data.DashboardData
import hivens.core.data.ModuleId
import hivens.core.time.Clock
import hivens.core.time.SystemClock
import hivens.launcher.cache.CacheFactory
import hivens.launcher.PackImportService
import hivens.launcher.PackInstallCoordinator
import hivens.launcher.curseforge.CurseForgeZipInstaller
import hivens.launcher.cache.ModrinthCaches
import hivens.launcher.cache.SmrtPackCaches
import hivens.launcher.catalogue.MirrorPackCatalogue
import hivens.launcher.catalogue.ModrinthPackCatalogue
import hivens.launcher.catalogue.PackArtResolver
import hivens.launcher.catalogue.PackCatalogueRegistry
import hivens.launcher.modrinth.ModrinthClient
import hivens.launcher.smrt.OpenSmrtHelperResolver
import hivens.launcher.smrt.SmartyModPlanner
import hivens.launcher.smrt.SmrtAuthlibSwapper
import hivens.launcher.smrt.SmrtPackClient
import hivens.launcher.smrt.SmrtSyncService
import hivens.launcher.update.DesktopIntegration
import hivens.launcher.update.SourceBuildService
import hivens.launcher.update.UpdateApplicators
import hivens.launcher.update.UpdateService
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import java.net.Authenticator
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.OkHttpClient
import org.koin.core.module.dsl.singleOf
import org.koin.core.qualifier.named
import org.koin.core.scope.Scope
import org.koin.dsl.module
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.nio.file.Path
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Module responsible for network interaction.
 */
val networkModule = module {

    single<Json> {
        Json {
            ignoreUnknownKeys = true
            prettyPrint = true
            isLenient = true
            encodeDefaults = true
            // Coerce unknown enum values to the field's default instead
            // of throwing. Without this, downgrading the launcher to a
            // build that does not yet declare a recently-added enum
            // variant (e.g. HomeView.New written by a newer build, read
            // by an older one) blows up SettingsService.reload() and
            // SilentlyResetsEverything to defaults -- the user loses
            // every other setting because of one unknown value.
            coerceInputValues = true
        }
    }

    // ── Smartycraft channel ───────────────────────────────────────────────────
    // SOCKS-proxied. Required for everything on `*.smartycraft.ru`. See the
    // routing taxonomy in [HttpClientProvider]'s KDoc.

    /**
     * Smartycraft secure client. SSL verification on, SOCKS proxy always on.
     * Backs the default (smartycraft) [HttpClientProvider]. Proxy creds and
     * host/port come from [ServerProtocolConfig] (Conduit Phase 3) so a
     * Mirror server with different proxy infra can plug in via config file.
     */
    single<OkHttpClient> {
        val cfg: ServerProtocolConfig = get()

        // Authenticator.setDefault is JVM-wide; SOCKS5 auth has no per-client
        // hook (OkHttp delegates SOCKS connect to JDK SocksSocketImpl which
        // only consults the global Authenticator). Scope the response so the
        // creds never leak to a third-party HTTP/HTTPS proxy that an unrelated
        // JVM caller might be talking to -- only this exact SOCKS host/port
        // gets answered.
        Authenticator.setDefault(object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication? {
                if (requestorType != RequestorType.PROXY) return null
                if (requestingHost != cfg.proxyHost) return null
                if (requestingPort != cfg.proxyPort) return null
                return PasswordAuthentication(cfg.proxyUser, cfg.proxyPass.toCharArray())
            }
        })

        OkHttpClient.Builder()
            .connectTimeout(cfg.connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(cfg.readTimeoutMs, TimeUnit.MILLISECONDS)
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(cfg.proxyHost, cfg.proxyPort)))
            .build()
    }

    /**
     * Smartycraft insecure client (SSL verification disabled).
     * Registered only for the explicit "connect anyway" user flow.
     * Never injected by default -- must be requested by `named("insecure")`.
     *
     * The default `HttpClientProvider` already returns this insecure
     * client when `NetworkState.bypassFor()` is true (see
     * `single<HttpClientProvider>` below), so a caller that has just
     * granted bypass via `NetworkState.grantBypass()` can switch back
     * to the regular `authService` and reach the same transport. The
     * `named("insecure")` chain (this client + dependent ChannelRouter
     * / IServerProtocol / AuthProvider) is therefore redundant for the
     * standard "Connect anyway" flow and is slated for removal once
     * the UI call sites are migrated. Until then it stays -- still
     * useful as a one-shot insecure transport that doesn't require
     * touching `NetworkState`.
     */
    single<OkHttpClient>(named("insecure")) {
        val cfg: ServerProtocolConfig = get()
        val (socketFactory, trustManager) = buildTrustAllSsl()

        OkHttpClient.Builder()
            .connectTimeout(cfg.connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(cfg.readTimeoutMs, TimeUnit.MILLISECONDS)
            .sslSocketFactory(socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true }
            .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress(cfg.proxyHost, cfg.proxyPort)))
            .build()
    }

    // ── Direct channel ────────────────────────────────────────────────────────
    // No proxy, strict TLS. For third-party CDNs (GitHub releases, BellSoft
    // JDKs, Maven Central). Survives any SMARTYcraft proxy outage by design --
    // the auto-updater must keep working when the upstream proxy doesn't.

    /**
     * Direct-channel client. No proxy, no SSL bypass. Backs the
     * [HttpClientProvider] qualified `named("direct")`.
     *
     * SSL bypass is intentionally not honored here: the third-party CDNs
     * we hit on this channel have rock-solid TLS, and silently widening the
     * bypass to them just because the user accepted it for smartycraft.ru
     * would be a needless trust expansion.
     */
    single<OkHttpClient>(named("direct")) {
        val cfg: ServerProtocolConfig = get()
        OkHttpClient.Builder()
            .connectTimeout(cfg.connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(cfg.readTimeoutMs, TimeUnit.MILLISECONDS)
            .build()
    }

    /**
     * Default (smartycraft) [HttpClientProvider] -- thin wrapper that resolves
     * the correct channel + cert mode on every request.
     *
     * Per-request decision (mirrors [ChannelRouter] for AuthProvider):
     *   - SSL bypass active for the smartycraft host -> insecure (proxy + no TLS check)
     *   - forceProxyMode toggle on -> secure proxy
     *   - default -> direct (no proxy)
     *
     * Pre-fix the provider always returned a proxy-only client regardless of
     * the user's force-proxy toggle, so `SkinManager` and `FileDownloadService`
     * never honored the setting -- the Settings switch only affected auth
     * routing via ChannelRouter, while skin and client-file traffic stayed
     * pinned to the SOCKS hop. Users whose network couldn't reach
     * `proxy.smartycraft.ru:58613` saw login work (direct-first via
     * ChannelRouter) but skins / news images / pack syncs fail silently.
     * Now every smartycraft.ru request reads [NetworkState] freshly, so the
     * Settings toggle takes effect on the next call without a relaunch.
     */
    single {
        val cfg: ServerProtocolConfig = get()
        val direct   = buildHttpClient(get<OkHttpClient>(named("direct")),   get())
        val secure   = buildHttpClient(get<OkHttpClient>(),                  get())
        val insecure = buildHttpClient(get<OkHttpClient>(named("insecure")), get())
        HttpClientProvider {
            when {
                NetworkState.bypassFor(cfg.sslBypassHost) -> insecure
                NetworkState.forceProxyMode()             -> secure
                else                                      -> direct
            }
        }
    }

    /**
     * Direct-channel [HttpClientProvider]. Inject this (`named("direct")`)
     * for any outbound call that does NOT need to tunnel through the
     * SMARTYcraft proxy -- see routing notes in [HttpClientProvider].
     */
    single<HttpClientProvider>(named("direct")) {
        val direct = buildHttpClient(get<OkHttpClient>(named("direct")), get())
        HttpClientProvider { direct }
    }

    /**
     * Smartycraft-routed `okhttp3.Call.Factory` for callers that consume the
     * OkHttp call API directly (Coil's image fetcher today; no Ktor [HttpClient]
     * adapter on its side). Mirrors the per-request channel decision the
     * default smartycraft [HttpClientProvider] makes; both must agree, or
     * Nexira's news strip and skin images would route differently from the
     * auth / protocol traffic that uses [HttpClientProvider].
     *
     * Keeping the two implementations in one file makes the divergence
     * surface concrete: any future change to the routing rule touches both
     * adjacent registrations under one diff.
     */
    single<Call.Factory> {
        val cfg: ServerProtocolConfig = get()
        val direct   = get<OkHttpClient>(named("direct"))
        val secure   = get<OkHttpClient>()
        val insecure = get<OkHttpClient>(named("insecure"))
        Call.Factory { request ->
            val client = when {
                NetworkState.bypassFor(cfg.sslBypassHost) -> insecure
                NetworkState.forceProxyMode()             -> secure
                else                                      -> direct
            }
            client.newCall(request)
        }
    }

    // ── Conduit (network refactor) ──────────────────────────────────────────
    // IServerProtocol abstracts all `*.smartycraft.ru` traffic so repositories
    // don't know URL paths or `action=` strings. ChannelRouter is the
    // direct-default + proxy-fallback gate (Conduit Phase 2):
    //
    // Default channel: ChannelRouter wraps a direct + a proxied OkHttpClient.
    // Each call tries direct first; on IOException retries via proxy. Users in
    // censored networks toggle Settings -> Network -> "Force proxy mode" to
    // skip the direct attempt (see NetworkState.forceProxyMode).
    //
    // Insecure channel: separate IServerProtocol bound for SSL-bypass login
    // retry -- uses a router that wraps the insecure-direct + insecure-proxy
    // pair so the bypass survives the fallback chain.
    //
    // Wire spec lives in docs/dev/smartycraft-v1-protocol.md.

    single<ChannelRouter> {
        ChannelRouter(
            direct = buildHttpClient(get<OkHttpClient>(named("direct")), get()),
            proxy  = buildHttpClient(get<OkHttpClient>(),                 get()),
        )
    }
    single<ChannelRouter>(named("insecure")) {
        // Insecure direct + insecure proxy. Used when user has accepted SSL
        // bypass for the host; fallback still works, just without TLS check.
        val cfg: ServerProtocolConfig = get()
        val (socketFactory, trustManager) = buildTrustAllSsl()
        val insecureDirect = OkHttpClient.Builder()
            .connectTimeout(cfg.connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(cfg.readTimeoutMs, TimeUnit.MILLISECONDS)
            .sslSocketFactory(socketFactory, trustManager)
            .hostnameVerifier { _, _ -> true }
            .build()
        ChannelRouter(
            direct = buildHttpClient(insecureDirect,                       get()),
            proxy  = buildHttpClient(get<OkHttpClient>(named("insecure")), get()),
        )
    }

    // ServerProtocolConfig -- Conduit Phase 3. Loads from
    // <dataDir>/server-config.json with smartycraft.ru defaults if absent.
    // Optional system-property override aura.conduit.baseurl gates a runtime
    // base URL change for Mirror development / test environments (gated by
    // ExperimentalConduitOverride opt-in inside the loader).
    single<ServerProtocolConfig> {
        ServerProtocolConfigLoader(get()).load(get<Path>())
    }

    // MsaConfig -- Microsoft OAuth client id, blank by default (sign-in disabled).
    // Loads from <dataDir>/msa-config.json; nexira.msa.clientId / NEXIRA_MSA_CLIENT_ID
    // override the client id. Blank keeps the launcher at Phase A behavior.
    single<MsaConfig> {
        MsaConfigLoader(get()).load(get<Path>())
    }

    single { LauncherHashCache(
        dataDir = get<Path>().toFile(),
        router  = get<ChannelRouter>(),
        config  = get<ServerProtocolConfig>(),
    ) }

    single<IServerProtocol> {
        SmartycraftV1Protocol(get<ChannelRouter>(), get(), get<LauncherHashCache>(), get<ServerProtocolConfig>())
    }
    single<IServerProtocol>(named("insecure")) {
        SmartycraftV1Protocol(
            get<ChannelRouter>(named("insecure")),
            get(),
            get<LauncherHashCache>(),
            get<ServerProtocolConfig>(),
        )
    }

    // Repositories -- thin adapters over IServerProtocol.
    single { ServerRepository(get<IServerProtocol>()) }
    single { SkinRepository(get<IServerProtocol>()) }
    single { PlayerRepository(get<IServerProtocol>()) }
}

// ── App composition modules ─────────────────────────────────────────────────
// The former monolithic appModule, split into intent-named modules so the
// auth/mirror extraction has clean seams to grab and the inline assembly
// (LoaderRegistry, SmrtPackCaches, the dashboard cache) lives in named
// factories below. All are registered together in LauncherBootstrap, so a
// definition's module membership does not affect resolution -- only grouping.

/**
 * Auth + credential storage seam. The load-bearing target of the client-auth
 * extraction: keyring, credential manager, and the SmartyCraft auth provider
 * (secure + insecure-bypass variants).
 */
val authModule = module {
    // Secret storage via libvault: OS keyring (Secret Service / Credential
    // Manager / Keychain) with an encrypted-file fallback, opened once for the
    // process. The credentials.vault blob sits next to credentials.json. On a
    // locked keyring the vault degrades to the file tier rather than prompting
    // (see CredentialsManager KDoc).
    single<SecretVault> {
        val ns = "io.github.kitty_hivens.Nexira"
        val file = get<Path>().resolve("credentials.vault")
        // Keyring disabled by boot recovery -> skip the OsKeyring tier (the DBus /
        // Secret Service probe that can hang on a hostile session) and fall to the
        // encrypted file, so saved credentials keep working without the keyring.
        val keyringOff = ModuleId.Keyring.id in get<ISettingsService>().getSettings().disabledModules
        val config = if (keyringOff) {
            VaultConfig(namespace = ns, softwareFilePath = file, preferredTiers = listOf(VaultTier.SoftwareFile, VaultTier.Memory))
        } else {
            VaultConfig(namespace = ns, softwareFilePath = file)
        }
        Vault.open(config)
    }
    // Legacy keyring + AES reader, kept one release for the migration shim. Lazy
    // single: built -- and the old keyring probed -- only when CredentialsManager
    // hits a pre-v5 credentials.json and resolves the provider lambda below.
    single { LegacyCredentialsManager(get(), get(), KeyringStorageFactory.system()) }
    single { CredentialsManager(get(), get(), get<SecretVault>(), legacyProvider = { get() }) }
    // Interface aliases for the launch-flow seam. LauncherController binds the
    // I* slices; other consumers keep the concrete type. get<Concrete>() reuses
    // the single instance rather than building a second.
    single<ICredentialStore> { get<CredentialsManager>() }
    single<AccountStore> { get<CredentialsManager>() }

    single<AuthProvider> { SmartyCraftAuthProvider(get<IServerProtocol>()) }

    /**
     * Insecure [AuthProvider] -- used exclusively for the SSL bypass login retry.
     * Always connects without certificate verification (via the insecure-channel
     * IServerProtocol variant bound above in coreModule).
     */
    single<AuthProvider>(named("insecure")) {
        SmartyCraftAuthProvider(get<IServerProtocol>(named("insecure")))
    }

    // Offline-play provider + the Microsoft provider + the registry the content
    // router and launch gate consult. Microsoft is always constructible but only
    // JOINS the registry -- and so surfaces in the login UI and activates its
    // launch gate -- when a client id is configured. It uses the proxy-free
    // "direct" HTTP client (login.microsoftonline.com / xboxlive must not go
    // through the SC SOCKS channel).
    single { OfflineAuthProvider() }
    single { MsaAuthProvider(get<HttpClientProvider>(named("direct")), get<MsaConfig>().clientId) }
    single {
        AuthProviderRegistry(
            buildList {
                add(get<AuthProvider>())
                add(get<OfflineAuthProvider>())
                if (get<MsaConfig>().enabled) add(get<MsaAuthProvider>())
            },
        )
    }
}

/**
 * Cross-cutting disk cache layer (TTL + stale-while-revalidate). CacheFactory
 * shares the app Json, the process-lifetime IO scope, and a system clock; the
 * per-endpoint pack-metadata namespaces live in the [smrtPackCaches] factory.
 */
val cacheModule = module {
    single<Clock> { SystemClock }
    single { CacheFactory(rootDir = get<Path>().resolve("cache"), json = get(), scope = get(), clock = get()) }
    single { smrtPackCaches() }
    single { modrinthCaches() }
}

/**
 * Hivens mirror: pack client + sync, the Smarty -> open-smrt interop swap, the
 * SC-bound authlib swap, the pack installers, and the per-mod icon resolver.
 */
val mirrorModule = module {
    // Hivens Mirror sync. Uses the "direct" HttpClient because
    // smrt.hivens.dev and Modrinth are public CDN-fronted endpoints
    // that don't need the SC channel's SOCKS proxy or SSL bypass.
    // Always wired so toggling on at runtime requires no graph rebuild.
    single { SmrtPackClient(get(named("direct")), caches = get()) }
    single<IMirrorPackClient> { get<SmrtPackClient>() }
    single { ModrinthClient(get(named("direct")), caches = get()) }
    single { SmrtSyncService(get(), get(), get()) }

    // Pack-catalogue read side: one provider per browsable source, indexed by
    // origin so the Browse UI stays source-agnostic.
    single { MirrorPackCatalogue(get()) }
    single { ModrinthPackCatalogue(get()) }
    single { PackCatalogueRegistry(listOf(get<MirrorPackCatalogue>(), get<ModrinthPackCatalogue>())) }
    // Resolves an installed instance's native cover from its source when the
    // install didn't capture one (pre-field instances), so Library cards and the
    // PackDetail hero show real art instead of the pixel placeholder.
    single { PackArtResolver(modrinth = get(), mirror = get()) }

    // Install write side: dispatches a (pack, version) by origin onto the
    // mirror sync installer or the Modrinth .mrpack installer.
    single { PackInstallCoordinator(mirrorInstaller = get(), mrpackInstaller = get(), mirrorClient = get()) }
    single {
        CurseForgeZipInstaller(
            json = get(),
            javaManager = get(),
            runtimeProvisioner = get(),
            repository = get(),
            dataDir = get(),
        )
    }
    single { PackImportService(mrpackInstaller = get(), cfInstaller = get()) }
    single<IPackSyncService> { get<SmrtSyncService>() }

    // Smarty -> open-smrt-network swap. Direct channel: GitHub releases +
    // raw.githubusercontent.com are public, no SC proxy. The planner is what
    // both sync paths (LauncherController, AutoSyncService) consult.
    single { OpenSmrtHelperResolver(get(named("direct")), get(), get()) }
    single { SmartyModPlanner(get<OpenSmrtHelperResolver>()::resolve, get()) }
    // SC-bound pack authlib swap. Default (smartycraft) channel: the patched jar
    // is pulled from the SC client distribution, same source as the server-list sync.
    single { SmrtAuthlibSwapper(get(), get<ServerProtocolConfig>(), get()) }
    single { PackInstaller(syncService = get(), runtimeProvisioner = get(), repository = get(), dataDir = get()) }
    single {
        MrpackInstaller(
            clientProvider = get(named("direct")),
            json = get(),
            javaManager = get(),
            runtimeProvisioner = get(),
            repository = get(),
            dataDir = get(),
        )
    }

    // Per-mod icon URL resolver for the Library PackDetail Content tab.
    // Direct iconUrl wins; otherwise resolves a Modrinth project's icon
    // via ModrinthClient. Results cached per project_id inside the
    // resolver instance.
    single {
        val client: ModrinthClient = get()
        ModIconResolver(
            resolveProjectIcon = { projectId -> client.resolveProject(projectId).iconUrl },
            resolveIconByHash  = { sha1 -> client.versionByHash(sha1)?.let { client.resolveProject(it.projectId).iconUrl } },
        )
    }
}

/**
 * Runtime provisioning + JVM command assembly: managed Java, natives + classpath
 * + command building, loader resolution (the [loaderRegistry] factory), vanilla /
 * loader library provisioning, and the adaptive-heap profiler.
 */
val runtimeModule = module {
    // Direct channel -- BellSoft JDK CDN does not require the SMARTYcraft proxy.
    single<IJavaManager> { JavaManagerService(get(), get(named("direct"))) }

    // Direct channel -- Maven Central LWJGL/JInput natives don't need the proxy.
    single { EnvironmentPreparer(get(named("direct"))) }
    single { ClasspathProvider(get()) }
    single { GameCommandBuilder(get()) }
    single { ProcessLogHandler() }

    // Canonical runtime provisioner -- vanilla + loader libraries from the
    // official Mojang/Forge CDNs into the shared roots. Direct channel: these
    // CDNs do not use the SMARTYcraft proxy (same rationale as JavaManagerService).
    single { ForgeLegacyResolver(get(named("direct")), get()) }
    single { loaderRegistry() }
    single {
        RuntimeProvisioner(
            librariesDir = get<PlatformPaths>().librariesDir,
            assetsDir = get<PlatformPaths>().assetsDir,
            clientProvider = get(named("direct")),
            json = get(),
            loaderRegistry = get(),
        )
    }

    // Adaptive-memory profiler: reads the agent's per-session metrics + persists
    // the per-instance derived-heap profile; extracts the agent jar to the data dir.
    single { ProfilerProfileStore(get()) }
    single { AgentExtractor(get<Path>()) }
}

/**
 * The launch flow: the orchestrator [LauncherController], the [ILauncherService]
 * that spawns the process, the file-download + manifest + profile collaborators
 * it drives, and the background AutoSyncService.
 */
val launchPipelineModule = module {
    single {
        val dataDir: Path = get()
        ManifestCache(dataDir.resolve("manifest-cache"), get())
    }
    single<IManifestStore> { get<ManifestCache>() }
    single<IFileDownloadService> { FileDownloadService(get(), get(), get(), get<ServerProtocolConfig>()) }
    single<IManifestProcessorService> { ManifestProcessorService(get()) }
    single { ProfileManager(get(), get()) }
    single<IInstanceProfileStore> { get<ProfileManager>() }

    /**
     * Launch-flow orchestrator. Consumes client-core interfaces, the shared
     * coroutine scope, and SmartyModPlanner -- the one concrete collaborator
     * left, since its nested Plan return type resists a clean interface. No UI
     * types (i18n strings, console service) leak in.
     */
    singleOf(::LauncherController)

    /**
     * Basic launch service. All collaborators are constructor-injected so the
     * facade is fully replaceable / mockable in tests.
     */
    single<ILauncherService> {
        LauncherService(
            profileManager     = get(),
            javaManager        = get(),
            envPreparer        = get(),
            classpathProvider  = get(),
            commandBuilder     = get(),
            logHandler         = get(),
            runtimeProvisioner = get(),
            profilerStore      = get(),
            agentExtractor     = get(),
            authlibSwapper     = get(),
            sharedAssetsDir    = get<PlatformPaths>().assetsDir,
            sharedLibrariesDir = get<PlatformPaths>().librariesDir,
        )
    }

    single {
        val dataDir: Path = get()
        val profiles: ProfileManager = get()
        val credentials: ICredentialStore = get()
        val settings: ISettingsService = get()
        AutoSyncService(
            authService = get(),
            downloadService = get(),
            manifestProcessor = get(),
            manifestCache = get(),
            dataDirectory = dataDir,
            credentialsProvider = { credentials.load() },
            optionalModsStateProvider = { serverId ->
                profiles.getProfile(serverId).optionalModsState
            },
            smartyPlanner = get(),
            settingsProvider = { settings.getSettings() },
        )
    }
}

/**
 * In-app update + build-from-source stack. Direct channel only -- GitHub
 * releases must stay reachable even when the SMARTYcraft proxy is down,
 * otherwise the auto-updater cannot ship the fix that restores connectivity.
 */
val updateModule = module {
    single {
        UpdateService(
            clientProvider  = get(named("direct")),
            json            = get(),
            dataDirectory   = get(),
            settingsService = get()
        )
    }

    // Per-platform update applicator selected at startup. Kept as a singleton
    // so the shutdown hook each implementation registers fires exactly once.
    single<IUpdateApplicator> { UpdateApplicators.forCurrentPlatform() }

    // Desktop-entry install (Linux/AppImage) + build-from-source (Dev/Git
    // channels). Both back the update manager; both no-op / report unsupported
    // off Linux.
    single { DesktopIntegration() }
    single { SourceBuildService(dataDirectory = get(), applicator = get()) }
}

/**
 * Core platform + persistence remainder: paths, settings, the shared coroutine
 * scope and its lifecycle hooks, crash reporting, and the server-list / pack /
 * layout-graph repositories -- the pieces every other module sits on.
 */
val appModule = module {
    /**
     * Per-OS application paths. See [PlatformPaths] for layout.
     */
    single(createdAtStart = true) { PlatformPaths.system() }

    /**
     * Application data directory. Resolved via [PlatformPaths] so that all
     * subsystems (settings, profiles, credentials, downloaded clients,
     * skin cache, logs, crash reports) share one platform-correct root.
     */
    single<Path>(createdAtStart = true) { get<PlatformPaths>().dataDir }

    /**
     * Crash report generator + dialog presenter. Main.kt constructs its
     * own instance pre-Koin for the uncaught-exception handler; this
     * registration covers post-Koin consumers (none today, but the
     * dependency contract makes it injectable for future Composables
     * that want to trigger a manual report).
     */
    single { CrashReporter(get()) }

    single<ISettingsService> {
        val dataDir: Path = get()
        SettingsService(get(), dataDir.resolve(Storage.SETTINGS_FILE))
    }

    // Replays persisted user-experimental overrides (forceProxyMode,
    // mimicVersionOverride) into their respective global state holders
    // on Koin start. `createdAtStart = true` makes this run during
    // `startKoin { modules(...) }` so the values are live before the
    // first protocol call.
    single(createdAtStart = true) { SettingsRestoreHook(get()) }

    /**
     * Process-lifetime coroutine scope for fire-and-forget background
     * work (tray-launch flow, AutoSync, `LauncherController.launch`).
     * SupervisorJob so a single failed child doesn't take down the
     * rest. Single shared scope across the whole launcher so the JVM
     * shutdown hook installed by [AppCoroutineScopeHook] cancels every
     * coroutine on process exit.
     */
    single<CoroutineScope>(createdAtStart = true) {
        CoroutineScope(
            SupervisorJob() + Dispatchers.IO
        )
    }

    // Installs the shutdown hook that cancels the scope above. Separated
    // from the scope's own factory so the factory stays a one-liner and
    // the hook can be tested independently if needed.
    single(createdAtStart = true) { AppCoroutineScopeHook(get()) }

    single {
        val dataDir: Path = get()
        ProtectedPaths(dataDir.resolve(Storage.PROTECTED_PATHS_FILE), get())
    }

    // Cache feeds the tray menu's first published DBusMenu layout before
    // the live fetch returns -- see [ServerListCacheStore] KDoc for the
    // "(No servers)" placeholder bug it fixes.
    single<ServerListCacheStore> {
        val dataDir: Path = get()
        JsonServerListCacheStore(
            file = dataDir.resolve(Storage.SERVERS_CACHE_FILE),
            json = get(),
        )
    }

    single<IServerListService> {
        SmartyCraftServerListService(get(), get(), get(), dashboardCache())
    }

    // JSON-on-disk pack registry. Persists installed PackInstances
    // to <dataDir>/packs.json so Library reflects real state across
    // launches. Empty file -> empty list (cold start UX is the
    // Library Empty CTA pointing at Browse).
    single<IPackRepository> {
        val dataDir: Path = get()
        JsonPackRepository(
            file = dataDir.resolve(Storage.PACKS_FILE),
            json = get(),
        )
    }

}

// ── Module factories ────────────────────────────────────────────────────────

/**
 * Pack-metadata cache namespaces. Browse listing + per-pack summary change
 * occasionally (serve stale for a day on outage); manifests change on a pack
 * release but pinned-version manifests are immutable (a week stale); Modrinth
 * project / version metadata rarely changes (a version is immutable).
 */
private fun Scope.smrtPackCaches(): SmrtPackCaches {
    val f: CacheFactory = get()
    val min = 60_000L
    val hour = 60 * min
    val day = 24 * hour
    return SmrtPackCaches(
        listing = f.create("pack-listing", SmrtPackListing.serializer(), CacheConfig(ttlMs = 5 * min, staleTtlMs = day)),
        summary = f.create("pack-summary", SmrtPackSummary.serializer(), CacheConfig(ttlMs = 10 * min, staleTtlMs = day)),
        manifest = f.create("pack-manifest", SmrtPackManifest.serializer(), CacheConfig(ttlMs = 10 * min, staleTtlMs = 7 * day)),
    )
}

/**
 * Modrinth metadata caches. A published project version is immutable, so the
 * version cache keeps a long stale window; project metadata changes rarely.
 */
private fun Scope.modrinthCaches(): ModrinthCaches {
    val f: CacheFactory = get()
    val min = 60_000L
    val hour = 60 * min
    val day = 24 * hour
    return ModrinthCaches(
        project = f.create("modrinth-project", ModrinthProject.serializer(), CacheConfig(ttlMs = hour, staleTtlMs = 7 * day)),
        version = f.create("modrinth-version", ModrinthVersion.serializer(), CacheConfig(ttlMs = 7 * day, staleTtlMs = 30 * day)),
    )
}

/**
 * In-memory dashboard cache (single-flight + 10-min SWR). The disk seed for the
 * tray stays in ServerListCacheStore (servers-only, read synchronously before
 * any coroutine); empty results don't get stored.
 */
private fun Scope.dashboardCache() =
    get<CacheFactory>().createInMemory<DashboardData>(
        "dashboard",
        CacheConfig(
            ttlMs = 10 * 60_000L,
            staleTtlMs = Long.MAX_VALUE,
            maxEntries = 4,
            shouldStore = { it.servers.isNotEmpty() },
        ),
    )

/**
 * Loader resolution registry. "forge" routes to legacy (<=1.12.2) or the modern
 * installer by MC version; modern loaders run the official installer headless,
 * caching output under loader-cache/ so re-launches skip the multi-minute install.
 */
private fun Scope.loaderRegistry(): LoaderRegistry {
    val loaderCacheDir: Path = get<Path>().resolve("loader-cache")
    return LoaderRegistry(
        listOf(
            ForgeResolver(
                legacy = get<ForgeLegacyResolver>(),
                modern = ModernInstallerResolver.forge(get(named("direct")), get(), get(), loaderCacheDir),
            ),
            ModernInstallerResolver.neoforge(get(named("direct")), get(), get(), loaderCacheDir),
            FabricLikeResolver(get(named("direct")), get(), "fabric", FabricLikeResolver.FABRIC_META),
            FabricLikeResolver(get(named("direct")), get(), "quilt", FabricLikeResolver.QUILT_META),
        ),
    )
}

// ── Helpers ───────────────────────────────────────────────────────────────────

/** Wraps the given [OkHttpClient] in a Ktor [HttpClient] with our shared timeouts, headers, and JSON content-negotiation. */
private fun buildHttpClient(okHttpInstance: OkHttpClient, json: Json): HttpClient =
    HttpClient(OkHttp) {
        engine { preconfigured = okHttpInstance }

        install(ContentNegotiation) { json(json) }

        install(HttpTimeout) {
            requestTimeoutMillis = 600_000
            connectTimeoutMillis = 30_000
            socketTimeoutMillis  = 600_000
        }

        defaultRequest {
            header("User-Agent", "SMARTYlauncher/${Protocol.MIMIC_LAUNCHER_VERSION}")
            contentType(ContentType.Application.Json)
        }
    }

/**
 * Builds a trust-all SSL socket factory for the insecure client.
 * Allows connecting to servers with expired certificates
 * when the user explicitly accepts the risk.
 */
private fun buildTrustAllSsl(): Pair<SSLSocketFactory, X509TrustManager> {
    val trustManager = object : X509TrustManager {
        override fun checkClientTrusted(
            chain: Array<X509Certificate>, authType: String
        ) = Unit
        override fun checkServerTrusted(
            chain: Array<X509Certificate>, authType: String
        ) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
    val ctx = SSLContext.getInstance("TLS")
    ctx.init(null, arrayOf(trustManager), SecureRandom())
    return ctx.socketFactory to trustManager
}
