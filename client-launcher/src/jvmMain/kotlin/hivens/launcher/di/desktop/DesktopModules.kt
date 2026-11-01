package hivens.launcher.di.desktop

import dev.hivens.libvault.SecretVault
import dev.hivens.libvault.Vault
import dev.hivens.libvault.VaultConfig
import dev.hivens.libvault.VaultTier
import hivens.auth.LazySecretVault
import hivens.core.api.interfaces.IJavaManager
import hivens.core.api.interfaces.ISettingsService
import hivens.core.api.interfaces.IUpdateApplicator
import hivens.core.data.ModuleId
import hivens.core.security.IKeyringStorage
import hivens.launcher.JavaManagerService
import hivens.launcher.component.ProcessLogHandler
import hivens.launcher.imports.FtbAppSource
import hivens.launcher.imports.ForeignInstanceImporter
import hivens.launcher.imports.LauncherImportService
import hivens.launcher.imports.LauncherRootLocator
import hivens.launcher.imports.MinecraftLauncherSource
import hivens.launcher.imports.ModrinthAppSource
import hivens.launcher.imports.PrismLauncherSource
import hivens.launcher.launch.GameHost
import hivens.launcher.launch.ProcessGameHost
import hivens.launcher.legacy.RetiredClientAdopter
import hivens.launcher.legacy.RetiredClientScanner
import hivens.launcher.platform.PlatformPaths
import hivens.launcher.runtime.RuntimeProvisioner
import hivens.launcher.runtime.loader.InstallerRunner
import hivens.launcher.runtime.loader.ProcessInstallerRunner
import hivens.launcher.security.KeyringStorageFactory
import hivens.update.DesktopIntegration
import hivens.update.UpdateApplicators
import hivens.update.UpdateService
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import java.nio.file.Path

/**
 * What the desktop supplies to the shared modules: where the files are, how a secret
 * is kept, which Java runs the game and how a game process is started.
 *
 * The shared modules ask for these by type and own none of them, which is what lets
 * another host (a phone) satisfy the same requests differently without the rest of
 * the graph being aware.
 */
val desktopHostModule = module {
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

    // The keyring the retired credential format was written to. Only the migration of
    // an old credentials file reads it, and it is built when that happens.
    single<IKeyringStorage> { KeyringStorageFactory.system() }

    // Direct channel -- the BellSoft JDK CDN keeps strict TLS.
    single<IJavaManager> { JavaManagerService(get(), get()) }
    single<InstallerRunner> { ProcessInstallerRunner(javaManager = get()) }

    single { ProcessLogHandler() }
    single<GameHost> { ProcessGameHost(javaManager = get(), logHandler = get()) }

    // Installs the shutdown hook that cancels the scope above. Separated
    // from the scope's own factory so the factory stays a one-liner and
    // the hook can be tested independently if needed.
    single(createdAtStart = true) { AppCoroutineScopeHook(get()) }
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
 * Reading what other launchers left behind, and adopting the clients the retired
 * SmartyCraft server path wrote. Both look in directories that exist on a desktop.
 */
val desktopImportModule = module {
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
        )
    }

    // What the retired SmartyCraft server path left under clients/. The scanner
    // reads it, the adopter turns one tree into a Local pack by hardlinking its
    // content, and the sweeper removes what the player chose to let go. None of
    // the three runs on its own; the surface above them asks first.
    single { RetiredClientScanner(get<PlatformPaths>().clientsDir) }
    single {
        val provisioner: RuntimeProvisioner = get()
        RetiredClientAdopter(
            ensureRuntime = { mc, loader, progress -> provisioner.ensureRuntime(mc, loader, "", progress).loaderVersion },
            javaManager = get(),
            repository = get(),
            dataDir = get(),
            assetsDir = get<PlatformPaths>().assetsDir,
        )
    }
}

/** Everything the desktop adds to [hivens.launcher.di.launcherModules]. */
fun desktopModules(): List<Module> = listOf(desktopHostModule, updateModule, desktopImportModule)
