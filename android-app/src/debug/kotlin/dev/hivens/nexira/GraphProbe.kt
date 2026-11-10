package dev.hivens.nexira

import android.util.Log
import hivens.auth.AccountStore
import hivens.auth.AuthProviderRegistry
import hivens.core.api.interfaces.IJavaManager
import hivens.core.api.interfaces.ILauncherService
import hivens.core.api.interfaces.IPackRepository
import hivens.core.api.interfaces.ISettingsService
import hivens.core.update.PackUpdater
import hivens.launcher.PackInstallService
import hivens.launcher.launch.LauncherController
import hivens.launcher.launch.GameHost
import hivens.launcher.platform.PlatformPaths
import kotlinx.coroutines.runBlocking
import org.koin.core.context.GlobalContext

/**
 * Asks the running graph for the things the application is entered through, then
 * reads and writes something real through it. The same check as the desktop's graph
 * test, on the device and with the Android host module.
 */
object GraphProbe {

    private const val TAG = "NexiraProbe"

    fun run() = runBlocking {
        val koin = GlobalContext.get()
        fun <T : Any> probe(name: String, get: () -> T) {
            val outcome = runCatching { get() }
            Log.i(TAG, "graph $name: " + outcome.fold({ "ok" }, { "FAILED ${it.javaClass.simpleName}: ${it.message?.take(160)}" }))
        }
        probe("paths") { koin.get<PlatformPaths>().also { Log.i(TAG, "graph dataDir=${it.dataDir}") } }
        probe("settings") { koin.get<ISettingsService>().also { it.getSettings() } }
        probe("launcher service") { koin.get<ILauncherService>() }
        probe("launcher controller") { koin.get<LauncherController>() }
        probe("pack updater") { koin.get<PackUpdater>() }
        probe("pack install service") { koin.get<PackInstallService>() }
        probe("auth providers") { koin.get<AuthProviderRegistry>() }
        probe("account store") { koin.get<AccountStore>().also { Log.i(TAG, "graph accounts=${it.listAccounts().size}") } }
        probe("game host") { koin.get<GameHost>() }
        probe("java manager") { koin.get<IJavaManager>() }
        probe("library") { koin.get<hivens.launcher.PackImportService>() }
        val repo = koin.get<IPackRepository>()
        Log.i(TAG, "graph pack registry lists ${repo.list().size} instance(s)")
        Log.i(TAG, "graph java manager bound: ${koin.getOrNull<IJavaManager>() != null} (the shipped runtime)")
    }
}
