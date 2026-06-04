package hivens.core.jvm

import com.sun.management.OperatingSystemMXBean
import java.lang.management.ManagementFactory
import org.slf4j.LoggerFactory

/**
 * Host physical RAM, read from com.sun.management's [OperatingSystemMXBean]
 * extension (the public, exported `getTotalMemorySize` accessor). Shared by the
 * RAM selector UI and the adaptive heap sizer so both read the same number.
 *
 * That extension lives in the `jdk.management` module: on the jlinked
 * distribution the module MUST be in the runtime image (see client-ui's
 * `packaging.modules` + the `verifyRuntimeModules` guard) or the class fails to
 * link and the read degrades to [FALLBACK_MB] -- which mis-sizes the Automatic
 * heap. The fallback logs a warning so such a regression is visible, not silent.
 */
object SystemMemory {

    /** Used when the platform does not expose physical RAM (e.g. jdk.management absent). */
    const val FALLBACK_MB = 16384

    private val logger = LoggerFactory.getLogger("SystemMemory")

    fun totalPhysicalMb(): Int {
        return try {
            val os = ManagementFactory.getOperatingSystemMXBean() as OperatingSystemMXBean
            (os.totalMemorySize / (1024 * 1024)).toInt().takeIf { it > 0 } ?: FALLBACK_MB
        } catch (_: LinkageError) {
            // com.sun.management failed to link: jdk.management is absent from the runtime
            // image. Degrade to a fallback rather than crash the launcher over a RAM read.
            logger.warn(
                "Could not read host RAM via com.sun.management OperatingSystemMXBean " +
                    "(jdk.management missing from the runtime image?). Using {} MB fallback.",
                FALLBACK_MB,
            )
            FALLBACK_MB
        }
    }
}
