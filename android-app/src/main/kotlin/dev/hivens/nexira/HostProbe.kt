package dev.hivens.nexira

import android.content.Context
import android.util.Log
import dev.hivens.nexira.host.AndroidGameHost
import dev.hivens.nexira.host.AndroidJavaManager
import hivens.core.data.LauncherLogType
import hivens.launcher.component.GameLaunch
import hivens.launcher.launch.PackPrepBlocked
import hivens.launcher.launch.RuntimeRequest
import hivens.launcher.runtime.AndroidJdkCatalog
import hivens.launcher.runtime.AndroidJdkInstaller
import hivens.launcher.runtime.AndroidJdkRelease
import org.koin.core.context.GlobalContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Path

/**
 * Drives the Android game host the way the launch service does, with a small Java
 * program in place of a game: prepare a runtime, start, read the output, wait for the
 * end. Scenarios: `hello` ends by itself, `exit7` calls System.exit(7), `terminate`
 * runs until asked to stop and must run its shutdown hook, `java8` asks for a runtime
 * this device does not have.
 *
 * The runtime is fetched the way the application fetches it, from the release given as
 * `url|sha256|version`, so a server on the development machine can stand in for the
 * repository that publishes the builds.
 */
object HostProbe {

    private const val TAG = "NexiraProbe"

    fun run(context: Context, scenario: String, release: String?) = runBlocking {
        val installer = GlobalContext.get().get<AndroidJdkInstaller>()
        val pinned = release?.split('|')?.let { (url, sha, version) -> AndroidJdkRelease(25, version, url, sha) }
        val manager = AndroidJavaManager(installer, AndroidJdkCatalog(listOfNotNull(pinned)))
        val jar = File(context.filesDir, "hello.jar")
        File(context.getExternalFilesDir(null), "hello.jar").takeIf { it.isFile }?.copyTo(jar, overwrite = true)

        val host = AndroidGameHost(context) { major, progress -> manager.homeFor(major, progress) }
        val major = if (scenario == "java8") 8 else 25
        val prepared = try {
            host.prepare(RuntimeRequest(major, null, null, true)) { m, _ -> Log.i(TAG, "host: $m") }
        } catch (e: PackPrepBlocked) {
            Log.i(TAG, "host refused: ${e.error}")
            return@runBlocking
        }

        val mainClass = when (scenario) {
            "exit7" -> "Exit7"
            "terminate" -> "Sleeper"
            "ffm" -> "Ffm"
            else -> "Hello"
        }
        var drained = false
        var after = false
        val handle = prepared.start(
            launch = GameLaunch(listOf("-cp", jar.absolutePath, "-Xmx192m", "--enable-native-access=ALL-UNNAMED"), mainClass, listOf("from", "host")),
            workDir = context.filesDir.toPath() as Path,
            boundLaunch = true,
            onLog = { line, type: LauncherLogType -> Log.i(TAG, "game[$type] $line") },
            onDrained = { drained = true },
            afterExit = { after = true },
        )
        if (scenario == "terminate") {
            delay(4_000)
            Log.i(TAG, "terminating")
            handle.terminate()
        }
        val code = withTimeout(90_000) { handle.awaitExit() }
        Log.i(TAG, "exit code $code, afterExit ran=$after")
        delay(800)
        Log.i(TAG, "output drained=$drained")
    }
}
