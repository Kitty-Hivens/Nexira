package hivens.core.jvm

import com.sun.management.OperatingSystemMXBean
import org.slf4j.LoggerFactory
import java.lang.management.ManagementFactory

private val logger = LoggerFactory.getLogger("SystemMemory")

internal actual val HOST_RESERVED_MB: Int = 0

internal actual fun readPhysicalMb(): Int? {
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
