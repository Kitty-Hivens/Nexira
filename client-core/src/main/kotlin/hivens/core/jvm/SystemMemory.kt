package hivens.core.jvm

import java.lang.management.ManagementFactory

/**
 * Host physical RAM. The figure comes from the `com.sun.management`
 * OperatingSystemMXBean extension, reached reflectively because
 * `getTotalPhysicalMemorySize` is not on the public `OperatingSystemMXBean`
 * interface. Shared by the RAM selector UI and the adaptive heap sizer so both
 * read the same number. Falls back to 16 GB when the platform does not expose it.
 */
object SystemMemory {
    fun totalPhysicalMb(): Int {
        val bean = ManagementFactory.getOperatingSystemMXBean()
        return try {
            val method = bean.javaClass.getMethod("getTotalPhysicalMemorySize")
            method.isAccessible = true
            ((method.invoke(bean) as Long) / (1024 * 1024)).toInt()
        } catch (_: Exception) {
            16384
        }
    }
}
