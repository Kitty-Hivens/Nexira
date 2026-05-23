package hivens.launcher.bootstrap

import org.slf4j.LoggerFactory
import java.awt.Toolkit

/**
 * Single startup line summarizing which AWT toolkit the JDK picked and what
 * Linux display-server environment we're in. Diagnostic value applies to
 * every Linux user; trivial to grep across `launcher.log` files attached to
 * crash bundles when a display issue gets reported.
 *
 * No-op outside Linux. Must be called after the toolkit has been touched
 * (typically [XToolkitOverride.applyLinuxAppClassName] does that) so the
 * `Toolkit.getDefaultToolkit()` lookup returns the actually-selected impl
 * rather than triggering selection here.
 */
object DisplayDiagnostics {

    private val log = LoggerFactory.getLogger("Main")

    fun logEnvironment() {
        if (!System.getProperty("os.name").lowercase().contains("linux")) return
        val toolkit = runCatching { Toolkit.getDefaultToolkit().javaClass.name }
            .getOrElse { "<unavailable: ${it.javaClass.simpleName}>" }
        fun env(k: String) = System.getenv(k) ?: "<unset>"
        log.info(
            "Display: toolkit={} XDG_SESSION_TYPE={} XDG_CURRENT_DESKTOP={} WAYLAND_DISPLAY={} DISPLAY={}",
            toolkit, env("XDG_SESSION_TYPE"), env("XDG_CURRENT_DESKTOP"),
            env("WAYLAND_DISPLAY"), env("DISPLAY"),
        )
    }
}
