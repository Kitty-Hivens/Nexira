package hivens.core.jvm

import java.io.File

// An 8 GB phone sits at about 3 GB in use with nothing of ours running, and a 4.5 GB heap had it killed on entering a world.
internal actual val HOST_RESERVED_MB: Int = 3000

// The kernel's own figure, which is what the memory manager of the phone sizes against.
internal actual fun readPhysicalMb(): Int? = runCatching {
    File("/proc/meminfo").useLines { lines ->
        lines.firstOrNull { it.startsWith("MemTotal:") }
            ?.split(Regex("\\s+"))?.getOrNull(1)?.toLongOrNull()
            ?.let { (it / 1024).toInt() }
            ?.takeIf { it > 0 }
    }
}.getOrNull()
