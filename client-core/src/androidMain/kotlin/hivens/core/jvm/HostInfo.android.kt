package hivens.core.jvm

import android.os.Build

internal actual fun readHostInfo(): HostInfo = HostInfo(
    osName = "Android",
    osVersion = "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
    osArch = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown",
    javaVersion = "ART ${System.getProperty("java.vm.version") ?: "unknown"}",
    javaVendor = System.getProperty("java.vendor") ?: "unknown",
)
