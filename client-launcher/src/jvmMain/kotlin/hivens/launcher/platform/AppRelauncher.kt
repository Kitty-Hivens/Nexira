package hivens.launcher.platform

import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Best-effort self-relaunch: release the single-instance lock, spawn the
 * launcher binary again, and let the caller exit -- the child (already started)
 * survives the parent's exit and acquires the freed lock. Used to apply a
 * recovery change (settings are cached at boot, so only a fresh process picks
 * them up) and to restart into recovery. Returns false when no relaunchable
 * binary exists (a Gradle/dev run, or an unsupported package): the caller then
 * quits and the user reopens by hand.
 */
object AppRelauncher {
    private val log = LoggerFactory.getLogger("AppRelauncher")

    fun relaunch(): Boolean {
        val exe = resolveBinary() ?: run {
            log.warn("No relaunchable binary (dev run or unsupported package); caller must quit")
            return false
        }
        return runCatching {
            // Free the lock BEFORE spawning so the child acquires it cleanly
            // instead of racing this process's shutdown-hook release.
            SingleInstance.release()
            ProcessBuilder(exe.toString())
                // A one-shot env var must not ride into the relaunched process,
                // or "continue to normal boot" would loop straight back into recovery.
                .apply { environment().remove("NEXIRA_RECOVERY") }
                .start()
            log.info("Relaunching {}", exe)
            true
        }.getOrElse {
            log.error("Relaunch failed; caller must quit", it)
            false
        }
    }

    /**
     * The on-disk launcher binary. AppImage first ($APPIMAGE is the real .AppImage
     * path; jpackage.app-path / proc-self-exe would resolve into the transient FUSE
     * mount that vanishes on exit). Then the jpackage launcher path (Windows exe,
     * macOS app, non-AppImage Linux). Then /proc/self/exe for a native Linux run.
     * Null on a dev/Gradle run -> the caller quits.
     */
    private fun resolveBinary(): Path? {
        System.getenv("APPIMAGE")?.takeIf { it.isNotBlank() }?.let { return Paths.get(it) }
        System.getProperty("jpackage.app-path")?.takeIf { it.isNotBlank() }?.let { return Paths.get(it) }
        return runCatching { Paths.get("/proc/self/exe").toRealPath() }.getOrNull()
    }
}
