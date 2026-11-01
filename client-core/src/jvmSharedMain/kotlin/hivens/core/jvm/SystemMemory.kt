package hivens.core.jvm

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

    private val cachedMb: Int? by lazy { readPhysicalMb() }

    /** Host physical RAM (MB), or null if the platform does not expose it (e.g. jdk.management absent). */
    fun totalPhysicalMbOrNull(): Int? = cachedMb

    /** Host physical RAM (MB) for sizing; [FALLBACK_MB] when the platform does not expose it. */
    fun totalPhysicalMb(): Int = cachedMb ?: FALLBACK_MB
}

internal expect fun readPhysicalMb(): Int?
