package hivens.ui.bootstrap

import hivens.config.Storage
import java.nio.file.Files
import java.nio.file.Path

/**
 * Pre-Koin resolution of "boot straight into recovery instead of the shell",
 * for the case where the launcher starts wrong but does not crash (a corrupt
 * layout, a module that hangs a surface) -- so the crash-loop latch never fires.
 * Any of three signals enters recovery:
 *   - the NEXIRA_RECOVERY env var (a truthy value),
 *   - the --recovery program argument,
 *   - a one-shot marker file written by the in-app "restart into recovery"
 *     action, consumed (deleted) here so it fires exactly once.
 * Defensive by construction: a read failure just means "no recovery".
 */
object RecoveryEntry {

    private val TRUTHY = setOf("1", "true", "yes", "on")

    fun resolve(dataDir: Path, args: Array<String>): Boolean {
        val fromEnv = System.getenv("NEXIRA_RECOVERY")?.trim()?.lowercase() in TRUTHY
        val fromArg = args.any { it == "--recovery" }
        val fromMarker = consumeMarker(dataDir)
        return fromEnv || fromArg || fromMarker
    }

    /** Arm the next boot to enter recovery -- the in-app "restart into recovery" writes
     *  this, then relaunches; [resolve] consumes it on the fresh process. */
    fun requestOnNextBoot(dataDir: Path) {
        runCatching { Files.writeString(dataDir.resolve(Storage.RECOVERY_REQUEST_FILE), "") }
    }

    /** True if the marker existed; delete it so recovery fires once, not every boot. */
    private fun consumeMarker(dataDir: Path): Boolean = runCatching {
        val marker = dataDir.resolve(Storage.RECOVERY_REQUEST_FILE)
        if (Files.isRegularFile(marker)) {
            Files.deleteIfExists(marker)
            true
        } else {
            false
        }
    }.getOrDefault(false)
}
