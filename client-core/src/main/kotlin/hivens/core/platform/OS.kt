package hivens.core.platform

/**
 * The host OS family. [classify] is the single place that folds an
 * `os.name` string into one of these; every consumer that needs an
 * OS-keyed token reads it off the resulting member instead of running
 * its own `contains(...)` ladder.
 *
 * `darwin` and `osx` fold into [MACOS]: some JVMs (Eclipse OpenJ9 in
 * particular) report `Darwin` rather than the common `Mac OS X`, and the
 * legacy LWJGL2 spelling is `osx`. The unix family (`nix`/`nux`/`aix`)
 * folds into [LINUX] -- those hosts ship and run the Linux JDK builds.
 */
enum class Platform {
    WINDOWS, MACOS, LINUX, UNKNOWN;

    /** Mojang piston-manifest os token (download rules + native classifiers). */
    val mojang: String
        get() = when (this) {
            WINDOWS -> "windows"
            MACOS -> "osx"
            // Mojang ships no "unknown" build; linux is the sanest fallback and
            // matches the historical else-branch behaviour.
            LINUX, UNKNOWN -> "linux"
        }

    /** BellSoft/Liberica JDK download os token. */
    val bellsoft: String
        get() = when (this) {
            WINDOWS -> "win"
            MACOS -> "mac"
            LINUX -> "linux"
            UNKNOWN -> "unknown"
        }

    /** LWJGL Maven Central native-classifier suffix (the modern "macos" form). */
    val lwjgl: String
        get() = when (this) {
            WINDOWS -> "windows"
            MACOS -> "macos"
            LINUX -> "linux"
            UNKNOWN -> "unknown"
        }

    /** Human-facing name. */
    val display: String
        get() = when (this) {
            WINDOWS -> "Windows"
            MACOS -> "macOS"
            LINUX -> "Linux"
            UNKNOWN -> "Unknown"
        }

    companion object {
        fun classify(osName: String): Platform {
            val n = osName.lowercase()
            return when {
                // "windows", not "win": "darwin" contains "win", so the short
                // form (which the old per-service ladders used) mis-tagged
                // Darwin-reporting macOS JVMs as Windows.
                n.contains("windows") -> WINDOWS
                n.contains("mac") || n.contains("darwin") || n.contains("osx") -> MACOS
                n.contains("nux") || n.contains("nix") || n.contains("aix") || n.contains("linux") -> LINUX
                else -> UNKNOWN
            }
        }
    }
}

/**
 * The host CPU architecture, coarse enough for picking native builds.
 * [classify] folds the many `os.arch` spellings (`amd64`, `x86_64`,
 * `aarch64`, `arm64`, `i386`, ...) into these.
 */
enum class Arch {
    ARM64, X64, X86, UNKNOWN;

    /** BellSoft/Liberica JDK download arch token. */
    val bellsoft: String
        get() = when (this) {
            ARM64 -> "arm64"
            X86 -> "x32"
            // x64 is the safe default for an unrecognised arch: it is the
            // overwhelmingly common host and a wrong guess surfaces as a clear
            // "no Java build for this system" download miss, not a silent crash.
            X64, UNKNOWN -> "x64"
        }

    companion object {
        fun classify(osArch: String): Arch {
            val a = osArch.lowercase()
            return when {
                a.contains("aarch64") || a.contains("arm64") -> ARM64
                a.contains("64") -> X64
                a.contains("86") || a.contains("32") -> X86
                else -> UNKNOWN
            }
        }
    }
}

/**
 * The current host, classified once per access off the live `os.name` /
 * `os.arch` system properties. Lives in `client-core` so launcher, ui and
 * auth modules share one classifier instead of each re-deriving "what
 * machine is this".
 *
 * Properties read the JVM property on every access rather than caching at
 * class-load: there is no live OS change to react to, but it keeps the
 * accessor honest under tests that pin `os.name`/`os.arch`.
 */
object OS {
    val platform: Platform get() = Platform.classify(System.getProperty("os.name", ""))
    val arch: Arch get() = Arch.classify(System.getProperty("os.arch", ""))

    val isWindows: Boolean get() = platform == Platform.WINDOWS
    val isMacOS: Boolean get() = platform == Platform.MACOS
    val isLinux: Boolean get() = platform == Platform.LINUX

    fun getName(): String = platform.display
}
