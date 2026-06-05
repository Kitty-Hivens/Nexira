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
 * link and the read is unavailable. [totalPhysicalMb] then substitutes
 * [FALLBACK_MB] for sizing; [totalPhysicalMbOrNull] returns null so a caller
 * that must not guess (a diagnostics display) can show "unknown" instead. The
 * read is memoized -- host RAM is constant per process -- so the warning on a
 * broken runtime fires once, not once per caller.
 */
object SystemMemory {

    /** Heap-sizing fallback used when the platform does not expose physical RAM. */
    const val FALLBACK_MB = 16384

    private val logger = LoggerFactory.getLogger("SystemMemory")

    private val cachedMb: Int? by lazy { readPhysicalMb() }

    /** Host physical RAM (MB), or null if the platform does not expose it (e.g. jdk.management absent). */
    fun totalPhysicalMbOrNull(): Int? = cachedMb

    /** Host physical RAM (MB) for sizing; [FALLBACK_MB] when the platform does not expose it. */
    fun totalPhysicalMb(): Int = cachedMb ?: FALLBACK_MB

    private fun readPhysicalMb(): Int? {
        return try {
            val os = ManagementFactory.getOperatingSystemMXBean() as OperatingSystemMXBean
            (os.totalMemorySize / (1024 * 1024)).toInt().takeIf { it > 0 }
        } catch (_: LinkageError) {
            // com.sun.management failed to link: jdk.management is absent from the runtime
            // image. Degrade rather than crash the launcher over a RAM read.
            logger.warn(
                "Could not read host RAM via com.sun.management OperatingSystemMXBean " +
                    "(jdk.management missing from the runtime image?).",
            )
            null
        }
    }
}
