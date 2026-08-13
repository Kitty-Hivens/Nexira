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
import hivens.launcher.network.CertificateTrustGate
import hivens.launcher.network.CertificateTrustInterceptor
import hivens.launcher.network.NetworkState
import hivens.launcher.network.MsaConfig
import hivens.launcher.network.MsaConfigLoader
import hivens.launcher.network.ServerProtocolConfig
import hivens.launcher.network.ServerProtocolConfigLoader
import hivens.launcher.protocol.LauncherHashCache
import hivens.launcher.protocol.SmartycraftV1Protocol
import hivens.core.api.HttpClientProvider
import hivens.core.net.TransferEngine
import hivens.core.api.PlayerRepository
import hivens.core.api.ServerRepository
import hivens.core.api.SkinRepository
import hivens.core.api.interfaces.*
import dev.hivens.libvault.SecretVault
import dev.hivens.libvault.Vault
import dev.hivens.libvault.VaultConfig
import dev.hivens.libvault.VaultTier
import hivens.auth.LazySecretVault
import hivens.launcher.*
import hivens.launcher.component.ClasspathProvider
import hivens.launcher.component.EnvironmentPreparer
import hivens.launcher.component.GameCommandBuilder
import hivens.launcher.component.ProcessLogHandler
import hivens.launcher.launch.LauncherController
import hivens.launcher.launch.RunningPackSource
import hivens.launcher.mrpack.MrpackInstaller
import hivens.launcher.AgentExtractor
import hivens.launcher.ProfilerProfileStore
import hivens.launcher.platform.PlatformPaths
import hivens.launcher.runtime.RuntimeProvisioner
import hivens.launcher.runtime.loader.CleanroomResolver
import hivens.launcher.runtime.loader.FabricLikeResolver
import hivens.launcher.runtime.loader.ForgeLegacyResolver
import hivens.launcher.runtime.loader.Lwjgl3ifyResolver
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
import hivens.core.data.NewsPage
import hivens.core.data.ModuleId
import hivens.core.time.Clock
import hivens.core.time.SystemClock
import hivens.launcher.cache.CacheFactory
import hivens.launcher.PackImportService
import hivens.launcher.PackInstallCoordinator
import hivens.launcher.PackInstallService
import hivens.launcher.PackOperationService
import hivens.launcher.imports.ForeignInstanceImporter
import hivens.launcher.imports.FtbAppSource
import hivens.launcher.imports.LocalPackCreator
import hivens.launcher.imports.LauncherImportService
import hivens.launcher.imports.LauncherRootLocator
import hivens.launcher.imports.MinecraftLauncherSource
import hivens.launcher.imports.ModrinthAppSource
import hivens.launcher.imports.PrismLauncherSource
import hivens.launcher.curseforge.CurseForgeZipInstaller
import hivens.launcher.cache.ModrinthCaches
import hivens.core.api.dto.smrt.SmrtManifestVersions
import hivens.launcher.cache.SmrtPackCaches
import hivens.core.io.IconProcessor
import hivens.core.update.PackUpdater
import hivens.core.update.PackUpdateStatusHub
import hivens.launcher.instance.ContentScanCache
import hivens.launcher.instance.InstanceContentScanner
import hivens.launcher.instance.InstanceSizeService
import hivens.launcher.instance.PackInstanceService
import hivens.launcher.news.SmartyCraftNewsFeed
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
import hivens.launcher.update.ApplyJournal
import hivens.launcher.update.ApplyRecovery
import hivens.launcher.update.PackAutoUpdateService
import hivens.launcher.update.PackSnapshotService
import hivens.launcher.update.PackUpdateService
import hivens.update.DesktopIntegration
import hivens.update.UpdateApplicators
import hivens.update.UpdateService
import io.ktor.client.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Protocol as HttpProtocol
import org.koin.core.module.dsl.singleOf
import org.koin.core.qualifier.named
import org.koin.core.scope.Scope
import org.koin.dsl.bind
import org.koin.dsl.module
import java.net.Socket
import java.nio.file.Path
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.slf4j.LoggerFactory

/**
 * Concurrent requests OkHttp will run against one host on the direct channel.
 * Matches the transfer engine's own ceiling; see the dispatcher note there.
 */
private const val MAX_REQUESTS_PER_HOST = 8

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

    /**
     * Where a refused certificate is parked for the shell to ask about. Held here
     * rather than in the UI module because the transport is what discovers the
     * refusal, and the launcher must not depend on the shell to report it.
     */
    single { CertificateTrustGate() }

    // ── Smartycraft channel ───────────────────────────────────────────────────
    // Everything on `*.smartycraft.ru`. See the routing taxonomy in
    // [HttpClientProvider]'s KDoc.

    /**
     * Smartycraft bypass client. Backs the explicit "connect anyway" user
     * flow; requested by `named("insecure")` or handed out by the default
     * [HttpClientProvider] once a grant exists, so a caller that has just
     * granted a bypass can stay on the regular `authService` and reach the
     * same transport.
     *
     * It is NOT a trust-nothing client: see [buildBypassScopedSsl]. Skipping
     * verification is scoped to a host the user granted, so this client
     * validates every other host exactly as the direct one does.
     */
    single<OkHttpClient>(named("insecure")) {
        val cfg: ServerProtocolConfig = get()
        val (socketFactory, trustManager) = buildBypassScopedSsl()

        OkHttpClient.Builder()
            .connectTimeout(cfg.connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(cfg.readTimeoutMs, TimeUnit.MILLISECONDS)
            .sslSocketFactory(socketFactory, trustManager)
            .hostnameVerifier(bypassScopedHostnameVerifier())
            .build()
    }

    // ── Direct channel ────────────────────────────────────────────────────────
    // Strict TLS, no per-host exceptions. Carries every outbound call the
    // launcher makes apart from the SSL-bypass escape hatch above.

    /**
     * Direct-channel client. Backs both the default [HttpClientProvider] and
     * the one qualified `named("direct")`.
     *
     * SSL bypass is intentionally not honored here: the third-party CDNs
     * we hit on this channel have rock-solid TLS, and silently widening the
     * bypass to them just because the user accepted it for smartycraft.ru
     * would be a needless trust expansion.
     */
    single<OkHttpClient>(named("direct")) {
        val cfg: ServerProtocolConfig = get()
        val trustGate: CertificateTrustGate = get()
        OkHttpClient.Builder()
            .connectTimeout(cfg.connectTimeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(cfg.readTimeoutMs, TimeUnit.MILLISECONDS)
            // A refused certificate on the smartycraft host becomes a question the
            // shell can ask, from whichever read hit it. Before this, only the login
            // form could ask -- so the roster and the news, neither of which needs a
            // session, were unreadable until the user had signed in.
            .addInterceptor(CertificateTrustInterceptor(trustGate) { cfg.sslBypassHost })
            // HTTP/1.1 only. This channel carries the pack downloads, which are
            // fetched one file at a time, so multiplexing buys nothing while h2's
            // framing adds a failure mode we have seen in the wild: a middlebox on
            // a filtered route resets the stream mid-body with PROTOCOL_ERROR and
            // the transfer dies. One request per connection has no stream to reset.
            .protocols(listOf(HttpProtocol.HTTP_1_1))
            // Raised in step with the transfer engine's permit ceiling. OkHttp allows
            // five requests per host by default, so a pool that grew past that would
            // queue inside the client, measure the extra permit as no gain, and hand
            // it straight back -- the engine would never be able to use what it asked
            // for. The engine, not this number, decides how many actually run.
            .dispatcher(Dispatcher().apply { maxRequestsPerHost = MAX_REQUESTS_PER_HOST })
            .build()
    }

    /**
     * Default (smartycraft) [HttpClientProvider] -- hands out the bypass
     * client while a grant for the smartycraft host is live, the direct one
     * otherwise. Reading [NetworkState] per request rather than at
     * construction is what lets a grant made mid-session take effect on the
     * next call instead of after a relaunch.
     *
     * This choice is routing, not enforcement: the bypass client scopes its
     * own relaxation to granted hosts, so picking it for a request to some
     * other host still validates that host normally.
     */
    single {
        val cfg: ServerProtocolConfig = get()
        val direct   = buildHttpClient(get<OkHttpClient>(named("direct")),   get())
        val insecure = buildHttpClient(get<OkHttpClient>(named("insecure")), get())
        HttpClientProvider {
            if (NetworkState.bypassFor(cfg.sslBypassHost)) insecure else direct
        }
    }

    /**
     * Direct-channel [HttpClientProvider]. Inject this (`named("direct")`)
     * for any outbound call that must never inherit a bypass the user
     * granted for the smartycraft host -- see routing notes in
     * [HttpClientProvider].
     */
    single<HttpClientProvider>(named("direct")) {
        val direct = buildHttpClient(get<OkHttpClient>(named("direct")), get())
        HttpClientProvider { direct }
    }

    /**
     * The one downloader. Every path that writes network bytes to disk goes
     * through it, so retry, resume, block-parallel transfer, verification and the
     * concurrency decision are the same wherever the bytes came from.
     *
     * Bound to the direct channel: the runtime CDNs, the mirror, the JDK hosts and
     * GitHub all keep strict TLS, and none of them has any business inheriting an
     * SSL bypass the user granted for the smartycraft host.
     */
    single { TransferEngine(get<HttpClientProvider>(named("direct"))) }

    /**
     * The same downloader on the smartycraft channel, for the bytes that come from
     * the SC client distribution.
     *
     * Two instances rather than one because the channel decision is not the
     * engine's to make: this one follows the bypass-aware provider, so a grant the
     * user made for the smartycraft host applies to its transfers and to nothing
     * else. They also each keep their own concurrency controller, which is right --
     * they are measuring different hosts.
     */
    single(named("smartycraft")) { TransferEngine(get<HttpClientProvider>()) }

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
        val direct   = get<OkHttpClient>(named("direct"))
        val insecure = get<OkHttpClient>(named("insecure"))
        Call.Factory { request ->
            // The request's own host, not the configured smartycraft one. This
            // factory backs the process-wide Coil loader, so keying on a fixed
            // host meant a grant for smartycraft also relaxed pack art from the
            // mirror and Modrinth.
            val client = if (NetworkState.bypassFor(request.url.host)) insecure else direct
            client.newCall(request)
        }
    }

    // ── Conduit (network refactor) ──────────────────────────────────────────
    // IServerProtocol abstracts all `*.smartycraft.ru` traffic so repositories
    // don't know URL paths or `action=` strings. The default binding follows
    // the bypass-aware provider; the `named("insecure")` one is pinned to the
    // trust-all client for the explicit "connect anyway" login retry.
    //
    // Wire spec lives in docs/dev/smartycraft-v1-protocol.md.

    single<HttpClientProvider>(named("insecure")) {
        val insecure = buildHttpClient(get<OkHttpClient>(named("insecure")), get())
        HttpClientProvider { insecure }
    }

    // ServerProtocolConfig -- Conduit Phase 3. Loads from
    // <dataDir>/server-config.json with smartycraft.ru defaults if absent.
    // Optional system-property override nexira.conduit.baseurl gates a runtime
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
        dataDir        = get<Path>().toFile(),
        clientProvider = get<HttpClientProvider>(),
        config         = get<ServerProtocolConfig>(),
    ) }

    single<IServerProtocol> {
        SmartycraftV1Protocol(get<HttpClientProvider>(), get(), get<LauncherHashCache>(), get<ServerProtocolConfig>())
    }
    single<IServerProtocol>(named("insecure")) {
        SmartycraftV1Protocol(
            get<HttpClientProvider>(named("insecure")),
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
        // Open lazily, off the Compose first-composition thread: the OsKeyring open
        // is a ~1.4s D-Bus probe, and resolving the account store eagerly in the
        // shell would put it on the UI thread and delay the boot-threshold reveal.
        // Every real consumer runs on Dispatchers.IO, so the open lands there.
        LazySecretVault {
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
    // launch gate -- when a client id is configured. It uses the "direct" HTTP
    // client so login.microsoftonline.com / xboxlive keep strict TLS whatever
    // bypass the user granted for the SC host.
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
    // Content-scan cache (Xodus-backed) + the scanner that reads it, so re-opening a
    // pack's Content tab reads parsed mod metadata from the DB instead of re-cracking
    // every jar. Keyed by canonical path, validated by size+mtime.
    single { ContentScanCache(get<CacheFactory>().environment(), "content-scan", get()) }
    // Icon processor is bound by the UI module (ImageIO lives outside the
    // headless engine); a GUI-less assembly scans without one.
    single { InstanceContentScanner(get(), getOrNull<IconProcessor>()) }
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
    // that must not inherit an SSL bypass granted for the SC host.
    // Always wired so toggling on at runtime requires no graph rebuild.
    single { SmrtPackClient(get(named("direct")), caches = get()) }
    single<IMirrorPackClient> { get<SmrtPackClient>() }
    single { ModrinthClient(get(named("direct")), get(), caches = get()) }
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
    // App-scoped owner of catalogue installs: runs the install on the shared
    // process scope (get<CoroutineScope>()) so navigating away from Browse does
    // not cancel a download mid-flight.
    single { PackInstallService(runInstall = get<PackInstallCoordinator>()::install, scope = get()) }
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
    // Foreign-launcher import (phase 1: discovery). Candidate-root locator spans
    // native XDG / Flatpak / Snap; one source per supported launcher.
    single { LauncherRootLocator() }
    single {
        LauncherImportService(
            sources = listOf(
                MinecraftLauncherSource(get(), get()),
                ModrinthAppSource(get()),
                PrismLauncherSource(get(), get()),
                FtbAppSource(get(), get()),
            ),
        )
    }
    // Import engine: copies a discovered instance's content and dedups a
    // vanilla-layout runtime into the shared roots (see ForeignInstanceImporter).
    single {
        ForeignInstanceImporter(
            runtimeProvisioner = get(),
            javaManager = get(),
            repository = get(),
            dataDir = get(),
            librariesDir = get<PlatformPaths>().librariesDir,
            assetsDir = get<PlatformPaths>().assetsDir,
        )
    }
    // Create an empty local pack from scratch (name + MC + loader); the Content
    // tab's Modrinth browser + local-jar add fill it in.
    single { LocalPackCreator(runtimeProvisioner = get(), javaManager = get(), repository = get(), dataDir = get()) }
    single<IPackSyncService> { get<SmrtSyncService>() }

    // Smarty -> open-smrt-network swap. Direct channel: GitHub releases +
    // raw.githubusercontent.com keep strict TLS. The planner is what both
    // sync paths (LauncherController, AutoSyncService) consult.
    single { OpenSmrtHelperResolver(get(named("direct")), get(), get(), get()) }
    single { SmartyModPlanner(get<OpenSmrtHelperResolver>()::resolve, get()) }
    // SC-bound pack authlib swap. Default (smartycraft) channel: the patched jar
    // is pulled from the SC client distribution, same source as the server-list sync.
    single { SmrtAuthlibSwapper(get(named("smartycraft")), get<ServerProtocolConfig>(), get()) }
    single { PackInstaller(syncService = get(), runtimeProvisioner = get(), repository = get(), dataDir = get()) }
    // Instance-level mutations that reach past the registry (full delete, detach).
    single { PackInstanceService(repository = get(), dataDir = get()) }
    // On-disk size of an instance, measured on the app scope and shared, so a
    // surface that asks again does not re-walk a tree the size of a world save.
    single { InstanceSizeService(dataDir = get(), scope = get(), clock = get()) }
    // App-scoped owner of the operations that rewrite an installed instance
    // (an update apply, a repair): one per instance, outliving the surface that
    // started it -- see PackOperationService.
    single { PackOperationService(scope = get(), sizes = get()) }
    // Update write side: moves an installed mirror instance to another build
    // (forward update or version switch) via the reconcile engine. Concrete
    // SmrtPackClient for the summary/version-list poll the interface slice lacks.
    single { PackSnapshotService(dataDir = get(), json = get()) }
    single { ApplyJournal(dataDir = get(), json = get()) }
    // Startup rollback for updates a hard crash interrupted (journal + snapshot).
    single { ApplyRecovery(snapshotService = get(), repository = get(), journal = get(), dataDir = get()) }
    // Also bound as the PackUpdater contract: the UI injects the interface so
    // render tests can substitute a fake; the auto-updater keeps the concrete type.
    single {
        PackUpdateService(
            client = get<SmrtPackClient>(),
            syncService = get(),
            repository = get(),
            snapshotService = get(),
            journal = get(),
            dataDir = get(),
        )
    } bind PackUpdater::class
    // Background auto-updater over installed mirror instances. Reads the current
    // auto-update policy each pass via the settings service. Also bound as the
    // status hub so UI badges and manual flows share one state.
    single {
        val settings = get<ISettingsService>()
        PackAutoUpdateService(
            repository = get(),
            updater = get<PackUpdateService>(),
            settingsProvider = { settings.getSettings() },
        )
    } bind PackUpdateStatusHub::class
    single {
        MrpackInstaller(
            transfers = get(),
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
    // Direct channel -- the BellSoft JDK CDN keeps strict TLS.
    single<IJavaManager> { JavaManagerService(get(), get()) }

    // Direct channel -- Maven Central LWJGL/JInput natives keep strict TLS.
    single { EnvironmentPreparer(get()) }
    single { ClasspathProvider(get()) }
    single { GameCommandBuilder(get()) }
    single { ProcessLogHandler() }

    // Canonical runtime provisioner -- vanilla + loader libraries from the
    // official Mojang/Forge CDNs into the shared roots. Direct channel: these
    // CDNs keep strict TLS (same rationale as JavaManagerService).
    single { ForgeLegacyResolver(get(named("direct")), get(), get()) }
    single { loaderRegistry() }
    single {
        RuntimeProvisioner(
            librariesDir = get<PlatformPaths>().librariesDir,
            assetsDir = get<PlatformPaths>().assetsDir,
            clientProvider = get(named("direct")),
            transfers = get(),
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
    single<IFileDownloadService> {
        FileDownloadService(get(named("smartycraft")), get(), get(), get<ServerProtocolConfig>())
    }
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

    // The slice the settings surfaces consume, so they do not pull the whole
    // orchestrator in to ask one question. Same instance, not a second one.
    single<RunningPackSource> { get<LauncherController>() }

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
 * releases must stay reachable however the SmartyCraft host is behaving,
 * otherwise the auto-updater cannot ship the fix that restores connectivity.
 */
val updateModule = module {
    single {
        UpdateService(
            clientProvider  = get(named("direct")),
            transfers       = get(),
            json            = get(),
            dataDirectory   = get(),
            settingsService = get(),
            applicator      = get(),
        )
    }

    // Per-platform update applicator selected at startup. Kept as a singleton
    // so the shutdown hook each implementation registers fires exactly once.
    single<IUpdateApplicator> { UpdateApplicators.forCurrentPlatform() }

    // Desktop-entry install (Linux/AppImage); the .desktop button in Advanced
    // backs this. No-op / reports unsupported off Linux.
    single { DesktopIntegration() }
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

    // Replays the persisted mimicVersionOverride into its global state
    // holder on Koin start. `createdAtStart = true` makes this run during
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
     *
     * The handler is not optional. SupervisorJob isolates siblings from a
     * failed child; it does not consume the throwable. Without a handler the
     * failure reaches `Thread.getDefaultUncaughtExceptionHandler`, which this
     * launcher wires to the crash reporter plus a modal "Nexira quit
     * unexpectedly" dialog -- shown on the EDT, i.e. the one thread Compose
     * draws on. A background pack-update push that throws would freeze the
     * window behind a report of a crash that did not happen, and invite the
     * user to file it. Fire-and-forget work failing is a log line.
     */
    single<CoroutineScope>(createdAtStart = true) {
        val log = LoggerFactory.getLogger("AppScope")
        CoroutineScope(
            SupervisorJob() + Dispatchers.IO +
                CoroutineExceptionHandler { context, throwable ->
                    log.error("background job failed in {}", context[CoroutineName]?.name ?: "app scope", throwable)
                }
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

    // The news archive, read from the site's paginated index rather than from the
    // dashboard payload -- which carries three entries and is why a widget asked
    // for twenty showed three. Same channel as the rest of the smartycraft
    // traffic; the dashboard stays the floor when the site cannot be read.
    single<INewsFeed> {
        SmartyCraftNewsFeed(
            clientProvider = get<HttpClientProvider>(),
            config = get(),
            dashboard = get(),
            cache = newsCache(),
        )
    }

    // Pack registry on Xodus (<dataDir>/db): installed PackInstances persisted one
    // entry per id so a mutation is an O(1) put, not a full-file rewrite. Migrates a
    // legacy packs.json on first open (renamed to *.migrated). Empty -> empty list.
    single<IPackRepository> {
        val dataDir: Path = get()
        XodusPackRepository(
            dbDir = dataDir.resolve("db"),
            legacyPacksFile = dataDir.resolve(Storage.PACKS_FILE),
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
        versions = f.create("pack-versions", SmrtManifestVersions.serializer(), CacheConfig(ttlMs = 5 * min, staleTtlMs = day)),
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
 * In-memory news-page cache (single-flight + 10-min SWR), keyed by page number.
 * Scrolling back up a rail, or reopening it, reads what was already fetched
 * instead of asking upstream for a page it just had; a page that came back empty
 * is not stored, so a failed read retries rather than sticking.
 */
private fun Scope.newsCache() =
    get<CacheFactory>().createInMemory<NewsPage>(
        "news",
        CacheConfig(
            ttlMs = 10 * 60_000L,
            staleTtlMs = Long.MAX_VALUE,
            maxEntries = 64,
            shouldStore = { it.items.isNotEmpty() },
        ),
    )

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
                modern = ModernInstallerResolver.forge(get(named("direct")), get(), get(), get(), loaderCacheDir),
            ),
            ModernInstallerResolver.neoforge(get(named("direct")), get(), get(), get(), loaderCacheDir),
            FabricLikeResolver(get(named("direct")), get(), "fabric", FabricLikeResolver.FABRIC_META),
            FabricLikeResolver(get(named("direct")), get(), "quilt", FabricLikeResolver.QUILT_META),
            CleanroomResolver(get(named("direct")), get(), get()),
            Lwjgl3ifyResolver(get(named("direct")), get()),
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
 * TLS for the bypass channel, scoped to the host the connection is actually
 * being made to.
 *
 * A bypass is a grant for one host. Building a client that trusts everything
 * and then choosing that client by a rule elsewhere makes the choosing rule
 * the security boundary, and a rule in a DI lambda is a poor place for one:
 * the process-wide Coil loader shares this client, so a grant for the
 * SmartyCraft host used to turn certificate checking off for pack art from
 * the mirror and from Modrinth too.
 *
 * Here the peer's own name decides. Verification is skipped only while
 * [NetworkState] holds a live grant for that exact host; every other host on
 * the same client gets full platform validation. Which client a call site
 * picks is then a routing detail, not a security decision.
 */
private fun buildBypassScopedSsl(): Pair<SSLSocketFactory, X509TrustManager> {
    val platform = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        .apply { init(null as KeyStore?) }
        .trustManagers
        .filterIsInstance<X509ExtendedTrustManager>()
        .first()

    val trustManager = object : X509ExtendedTrustManager() {
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) {
            if (granted(peerHostOf(socket))) return
            platform.checkServerTrusted(chain, authType, socket)
        }

        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) {
            if (granted(engine?.peerHost)) return
            platform.checkServerTrusted(chain, authType, engine)
        }

        /**
         * The socket-less overload carries no peer identity, so there is no
         * host to match a grant against. Validate for real rather than guess.
         * OkHttp always takes one of the overloads above.
         */
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) =
            platform.checkServerTrusted(chain, authType)

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, socket: Socket?) =
            platform.checkClientTrusted(chain, authType, socket)

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String, engine: SSLEngine?) =
            platform.checkClientTrusted(chain, authType, engine)

        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
            platform.checkClientTrusted(chain, authType)

        override fun getAcceptedIssuers(): Array<X509Certificate> = platform.acceptedIssuers
    }

    val ctx = SSLContext.getInstance("TLS")
    ctx.init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
    return ctx.socketFactory to trustManager
}

/** True when the user currently holds a bypass for [host]. Null host never matches. */
private fun granted(host: String?): Boolean = host != null && NetworkState.bypassFor(host)

/**
 * Peer name for an in-progress handshake. `handshakeSession` is the JSSE hook
 * meant for exactly this window; `socket.inetAddress` is deliberately not used,
 * since it would resolve a name by reverse DNS and match a grant against
 * something the user never agreed to.
 */
private fun peerHostOf(socket: Socket?): String? =
    (socket as? SSLSocket)?.handshakeSession?.peerHost

/**
 * Hostname verification for the bypass channel: skipped for a host under a
 * live grant, the platform check for everything else.
 */
private fun bypassScopedHostnameVerifier(): HostnameVerifier {
    val platform = HttpsURLConnection.getDefaultHostnameVerifier()
    return HostnameVerifier { host, session -> granted(host) || platform.verify(host, session) }
}
