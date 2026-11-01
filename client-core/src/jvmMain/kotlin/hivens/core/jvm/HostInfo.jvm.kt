package hivens.core.jvm

internal actual fun readHostInfo(): HostInfo = HostInfo(
    osName = System.getProperty("os.name") ?: "unknown",
    osVersion = System.getProperty("os.version") ?: "unknown",
    osArch = System.getProperty("os.arch") ?: "unknown",
    javaVersion = System.getProperty("java.version") ?: "unknown",
    javaVendor = System.getProperty("java.vendor") ?: "unknown",
)
