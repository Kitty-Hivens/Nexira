package dev.hivens.nexira.host

import android.content.Context
import dev.hivens.libvault.SecretVault
import dev.hivens.libvault.VaultConfig
import dev.hivens.libvault.android.AndroidVault
import hivens.core.api.interfaces.IJavaManager
import hivens.auth.LazySecretVault
import hivens.core.net.TransferEngine
import hivens.core.security.IKeyringStorage
import hivens.launcher.launch.GameHost
import hivens.launcher.platform.PlatformPaths
import hivens.launcher.runtime.AndroidJdkInstaller
import hivens.launcher.runtime.AndroidNativesCatalog
import hivens.launcher.runtime.AndroidNativesInstaller
import hivens.launcher.runtime.HostNativeSource
import hivens.launcher.security.NoOpKeyringStorage
import org.koin.core.module.Module
import org.koin.dsl.module
import java.nio.file.Path

/**
 * What a phone supplies to the shared launcher modules: where the files are, how a
 * secret is kept, and how a game process is started.
 *
 * The counterpart of the desktop's host module. The Java manager is the one that
 * fetches the pinned runtime for a major, there is no installer runner (the
 * loaders that need an installer are left out of the registry) and no old OS keyring
 * to migrate from, so the shared modules get what they ask for and nothing more.
 */
fun androidHostModule(context: Context): Module = module {
    val app = context.applicationContext

    // The paths class takes its environment as a function, which is how a data
    // directory is named on a host with no home directory to speak of.
    single<PlatformPaths>(createdAtStart = true) {
        PlatformPaths(
            osName = "Linux",
            home = app.filesDir.toPath(),
            bootstrapDataDir = { null },
            env = { name -> if (name == "NEXIRA_DATA_DIR") app.filesDir.absolutePath else null },
        )
    }
    single<Path>(createdAtStart = true) { get<PlatformPaths>().dataDir }

    // Opened on first use, off the thread that asks: the first open of a Keystore key can take a while.
    single<SecretVault> {
        LazySecretVault { AndroidVault.open(app, VaultConfig(namespace = "dev.hivens.nexira")) }
    }
    single<IKeyringStorage> { NoOpKeyringStorage }

    single {
        val engine = get<TransferEngine>()
        AndroidJdkInstaller(get<Path>().resolve("runtimes")) { transfer, progress -> engine.fetch(transfer, progress) }
    }
    // Mojang's natives are glibc builds, so the phone brings its own for the LWJGL release a game carries
    single {
        val engine = get<TransferEngine>()
        AndroidNativesInstaller(get<Path>().resolve("natives"), AndroidNativesCatalog.PINNED) { transfer, progress -> engine.fetch(transfer, progress) }
    }
    single<HostNativeSource> { get<AndroidNativesInstaller>() }
    single { AndroidJavaManager(get()) }
    single<IJavaManager> { get<AndroidJavaManager>() }
    single<GameHost> { AndroidGameHost(app) { major, progress -> get<AndroidJavaManager>().homeFor(major, progress) } }
}
