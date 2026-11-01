package hivens.core.jvm

/** What the running process says about the system and the runtime it sits on. */
data class HostInfo(
    val osName: String,
    val osVersion: String,
    val osArch: String,
    val javaVersion: String,
    val javaVendor: String,
) {
    /** The one line reports carry for the system. */
    val os: String get() = "$osName $osVersion ($osArch)"
}

/**
 * Read once: the properties do not change for the life of the process. Not read from
 * system properties by the callers, since a phone's runtime answers `0` for the Java
 * version and says nothing of the system it is on.
 */
object HostSystem {
    val info: HostInfo by lazy { readHostInfo() }
}

internal expect fun readHostInfo(): HostInfo
