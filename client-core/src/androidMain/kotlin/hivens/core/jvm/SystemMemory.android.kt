package hivens.core.jvm

import java.io.File

// The kernel's own figure, which is what the memory manager of the phone sizes against.
internal actual fun readPhysicalMb(): Int? = runCatching {
    File("/proc/meminfo").useLines { lines ->
        lines.firstOrNull { it.startsWith("MemTotal:") }
            ?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull()
            ?.let { (it / 1024).toInt() }
            ?.takeIf { it > 0 }
    }
}.getOrNull()
