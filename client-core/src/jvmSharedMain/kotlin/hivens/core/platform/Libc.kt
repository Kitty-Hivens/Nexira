package hivens.core.platform

/**
 * The C library the running process is linked against, which decides whether a
 * native build made for another one will start at all.
 *
 * Android reports `os.name = Linux`, and so does Alpine, so the OS family cannot
 * tell them from a glibc desktop. Mojang's `natives-linux*` and every JDK download
 * for Linux are built against glibc, and neither runs on musl or on bionic.
 *
 * [UNKNOWN] is the answer for a host with no such axis (Windows, macOS) and for a
 * Linux that could not be identified. Callers treat the second as glibc, which is
 * what every host here was assumed to be before this existed.
 */
enum class Libc {
    GLIBC, MUSL, BIONIC, UNKNOWN;

    companion object {
        /**
         * Names the libc from the memory map of the running process.
         *
         * The map lists the dynamic loader and the C library the process really
         * loaded, so it describes this process and not the files lying around the
         * machine: Alpine with a glibc compatibility layer installed has both
         * loaders on disk and still runs on musl. Bionic is checked first because
         * its paths name it; musl before glibc because a musl map holds no
         * `libc.so.6` and a glibc one holds no `ld-musl`.
         */
        fun classify(platform: Platform, memoryMap: String?): Libc {
            if (platform != Platform.LINUX || memoryMap.isNullOrEmpty()) return UNKNOWN
            return when {
                "/bionic/" in memoryMap || "/linker64" in memoryMap || "/system/bin/linker" in memoryMap -> BIONIC
                "ld-musl" in memoryMap -> MUSL
                "ld-linux" in memoryMap || "/libc.so.6" in memoryMap || "libc-2." in memoryMap -> GLIBC
                else -> UNKNOWN
            }
        }
    }
}
