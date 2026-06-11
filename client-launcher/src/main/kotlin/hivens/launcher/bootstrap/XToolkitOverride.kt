package hivens.launcher.bootstrap

import hivens.config.Branding
import hivens.core.platform.OS
import org.slf4j.LoggerFactory
import java.awt.Toolkit

/**
 * Forces the X11 toolkit's app class name so the WM_CLASS hint on every
 * window matches `StartupWMClass=` in the .desktop entry.
 *
 * Stock OpenJDK derives WM_CLASS from the launcher binary's argv[0] and
 * exposes no public knob to override it; JBR exposes `-Dawt.appClassName`
 * but only that one vendor honors it. Reflection into the package-private
 * static field works across both -- provided we run before any window is
 * shown (XWindow.setWMClass snapshots the value at construction time) and
 * the JVM was launched with `--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED`.
 *
 * No-op on macOS/Windows. Failures are logged but never fatal -- a wrong
 * compositor icon is annoying, not crash-worthy.
 */
object XToolkitOverride {

    private val log = LoggerFactory.getLogger(XToolkitOverride::class.java)

    fun applyLinuxAppClassName() {
        if (!OS.isLinux) return
        runCatching {
            // Triggers XToolkit class load + initial awtAppClassName assignment.
            Toolkit.getDefaultToolkit()
            val cls = Class.forName("sun.awt.X11.XToolkit")
            val field = cls.getDeclaredField("awtAppClassName")
            field.isAccessible = true
            field.set(null, Branding.WM_CLASS)
        }.onFailure {
            log.warn(
                "Could not override XToolkit.awtAppClassName ({}); " +
                "compositors may show a generic icon. Cause: {}",
                Branding.WM_CLASS, it.toString()
            )
        }
    }
}
