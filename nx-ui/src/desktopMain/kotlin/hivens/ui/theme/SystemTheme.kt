package hivens.ui.theme

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeUnit

private val log = LoggerFactory.getLogger("SystemTheme")

/**
 * Reads the OS colour scheme -- the Rule 7 "system colour-scheme listener feeding
 * NxTheme". One-shot subprocess probes, no daemons and no native bindings:
 *
 *  - Linux: the XDG desktop portal `Settings.Read` of `org.freedesktop.appearance`
 *    `color-scheme` via `gdbus`, falling back to `busctl`. The legacy `Read` call
 *    works on every portal version (`ReadOne` only exists since 1.15). A session
 *    with no Settings backend (e.g. Hyprland without the gtk portal or darkman)
 *    errors out and reads as "unknown".
 *  - Windows: `reg query` of `AppsUseLightTheme` (the value line is not localized).
 *  - macOS: `defaults read -g AppleInterfaceStyle` -- present and "Dark" when dark;
 *    the key simply does not exist in light mode, so a nonzero exit means light.
 *
 * `true` = the OS prefers dark, `false` = light, `null` = cannot tell (no portal
 * backend, no-preference answer, missing binaries, timeout). Callers surface the
 * null as an unavailable capability, not an error.
 */
object SystemTheme {

    private const val PROBE_TIMEOUT_SECONDS = 4L

    suspend fun probe(): Boolean? = withContext(Dispatchers.IO) {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        when {
            os.contains("win")                      -> probeWindows()
            os.contains("mac") || os.contains("darwin") -> probeMac()
            os.contains("nux") || os.contains("nix")    -> probeLinux()
            else -> null
        }
    }

    /**
     * The OS scheme as a cold polling flow: emits [probe] immediately, then every
     * [pollMs]. Polling runs only while collected, so subscribing exactly while the
     * System theme mode is active costs nothing in the other modes.
     */
    fun observe(pollMs: Long = 15_000): Flow<Boolean?> = flow {
        while (true) {
            emit(probe())
            delay(pollMs)
        }
    }

    private fun probeLinux(): Boolean? {
        val gdbus = run(
            "gdbus", "call", "--session",
            "--dest", "org.freedesktop.portal.Desktop",
            "--object-path", "/org/freedesktop/portal/desktop",
            "--method", "org.freedesktop.portal.Settings.Read",
            "org.freedesktop.appearance", "color-scheme",
        )
        if (gdbus != null && gdbus.exit == 0) return parsePortalColorScheme(gdbus.stdout)
        val busctl = run(
            "busctl", "--user", "call",
            "org.freedesktop.portal.Desktop", "/org/freedesktop/portal/desktop",
            "org.freedesktop.portal.Settings", "Read",
            "ss", "org.freedesktop.appearance", "color-scheme",
        )
        if (busctl != null && busctl.exit == 0) return parsePortalColorScheme(busctl.stdout)
        return null
    }

    private fun probeWindows(): Boolean? {
        val reg = run(
            "reg", "query",
            "HKCU\\SOFTWARE\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize",
            "/v", "AppsUseLightTheme",
        ) ?: return null
        if (reg.exit != 0) return null
        return parseAppsUseLightTheme(reg.stdout)
    }

    private fun probeMac(): Boolean? {
        val defaults = run("defaults", "read", "-g", "AppleInterfaceStyle") ?: return null
        return parseAppleInterfaceStyle(defaults.exit, defaults.stdout)
    }

    private class ProbeResult(val exit: Int, val stdout: String)

    // stdout is captured separately from stderr on purpose: macOS reports the
    // light-mode "no such key" on stderr, and the portal error text must not
    // reach the parsers.
    private fun run(vararg cmd: String): ProbeResult? = try {
        val process = ProcessBuilder(*cmd).start()
        val stdout = StringBuilder()
        val outDrain = Thread {
            process.inputStream.bufferedReader().forEachLine { stdout.appendLine(it) }
        }.apply { isDaemon = true; start() }
        Thread { process.errorStream.bufferedReader().forEachLine { } }
            .apply { isDaemon = true; start() }
        if (!process.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            process.waitFor(3, TimeUnit.SECONDS)
            null
        } else {
            outDrain.join(1_000)
            ProbeResult(process.exitValue(), stdout.toString())
        }
    } catch (e: Exception) {
        log.debug("scheme probe failed to start: {} ({})", cmd.firstOrNull(), e.toString())
        null
    }
}

private val PORTAL_UINT = Regex("""(?:uint32|\bu)\s+(\d+)""")
private val REG_DWORD = Regex("""AppsUseLightTheme\s+REG_DWORD\s+0x([0-9a-fA-F]+)""")

/**
 * The portal's `color-scheme` value out of gdbus (`(<<uint32 1>>,)`) or busctl
 * (`v v u 1`) output -- tolerant of single vs double variant nesting. Per the
 * spec: 1 = prefer dark, 2 = prefer light, 0/anything else = no preference.
 */
internal fun parsePortalColorScheme(stdout: String): Boolean? =
    when (PORTAL_UINT.findAll(stdout).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()) {
        1 -> true
        2 -> false
        else -> null
    }

/** `AppsUseLightTheme REG_DWORD 0x0` -> dark; nonzero -> light; missing -> null. */
internal fun parseAppsUseLightTheme(stdout: String): Boolean? =
    REG_DWORD.find(stdout)?.groupValues?.get(1)?.toLongOrNull(16)?.let { it == 0L }

/** `defaults read -g AppleInterfaceStyle`: exit 0 + "Dark" = dark; a nonzero exit
 *  means the key does not exist, which is how macOS signals light mode. */
internal fun parseAppleInterfaceStyle(exit: Int, stdout: String): Boolean =
    exit == 0 && stdout.trim().equals("Dark", ignoreCase = true)
