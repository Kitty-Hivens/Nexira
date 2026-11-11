package hivens.launcher.runtime

import hivens.core.platform.Libc
import hivens.core.platform.OS

/**
 * What a downloaded Java has to run on: the operating system, the CPU and the C
 * library, in the spelling the download hosts use.
 *
 * The C library is part of the answer because a Linux JDK is built against one: a
 * glibc build does not start on Alpine and does not start on Android. Taken from the
 * OS family alone, a musl host was handed a glibc JDK, unpacked it, and learned
 * that it was broken from `java -version` after a two hundred megabyte download.
 */
internal data class RuntimeTarget(val os: String, val arch: String, val libc: Libc) {

    /** The C library of a Linux host, with an unidentified one taken as the glibc every host here was once assumed to have. */
    val linuxLibc: Libc get() = if (os == "linux") libc else Libc.UNKNOWN

    /**
     * Added to names on disk. Empty for glibc and for everything that is not Linux,
     * so a runtime already installed keeps the folder it has.
     */
    val folderSuffix: String
        get() = when (linuxLibc) {
            Libc.MUSL -> "-musl"
            Libc.BIONIC -> "-bionic"
            else -> ""
        }

    /** For a message to a person: `linux x64`, `linux arm64 musl`. */
    val label: String get() = "$os $arch${folderSuffix.replace('-', ' ')}"

    companion object {
        fun current() = RuntimeTarget(OS.platform.bellsoft, OS.arch.bellsoft, OS.libc)
    }
}

/** One place a JDK archive can be fetched from. Null means this host has no build there. */
internal interface JdkSource {
    fun url(major: Int, target: RuntimeTarget): String?
}

/**
 * The mirrors to try, in order. Adoptium is the fallback because BellSoft (via
 * CloudFlare) blanket-403s certain regions and IP ranges that the user's network may
 * sit behind. Android has no entry: its Java is shipped with the launcher, not fetched.
 */
internal object JdkCatalog {
    private val sources: List<JdkSource> = listOf(BellSoftSource, AdoptiumSource)

    fun urls(major: Int, target: RuntimeTarget): List<String> =
        if (target.os == "linux" && target.linuxLibc == Libc.BIONIC) emptyList()
        else sources.mapNotNull { it.url(major, target) }
}

internal object BellSoftSource : JdkSource {

    private const val ROOT = "https://download.bell-sw.com/java"

    /**
     * The musl builds carry no `-full` bundle (that is the one with JavaFX, which the
     * game does not use) and the aarch64 ones are the plain JDK throughout, so the
     * names below are the ones that exist for every pinned major.
     */
    override fun url(major: Int, target: RuntimeTarget): String? {
        val os = target.os
        val arch = target.arch
        if (os == "linux" && target.linuxLibc == Libc.BIONIC) return null
        if (os == "linux" && target.linuxLibc == Libc.MUSL) {
            val build = PINS[major] ?: return null
            return when (arch) {
                "x64" -> "$ROOT/$build/bellsoft-jdk$build-linux-x64-musl.tar.gz"
                "arm64" -> "$ROOT/$build/bellsoft-jdk$build-linux-aarch64-musl.tar.gz"
                else -> null
            }
        }
        return when (major) {
            8 -> when (os) {
                "win" if arch == "x64" -> "$ROOT/8u472+9/bellsoft-jdk8u472+9-windows-amd64-full.zip"
                "win" if arch == "x32" -> "$ROOT/8u472+9/bellsoft-jdk8u472+9-windows-i586.zip"
                "linux" if arch == "x64" -> "$ROOT/8u472+9/bellsoft-jdk8u472+9-linux-amd64-full.tar.gz"
                "linux" if arch == "arm64" -> "$ROOT/8u472+9/bellsoft-jdk8u472+9-linux-aarch64.tar.gz"
                "mac" if arch == "x64" -> "$ROOT/8u472+9/bellsoft-jdk8u472+9-macos-amd64-full.tar.gz"
                "mac" if arch == "arm64" -> "$ROOT/8u472+9/bellsoft-jdk8u472+9-macos-aarch64.tar.gz"
                else -> null
            }
            17 -> when (os) {
                "win" if arch == "x64" -> "$ROOT/17.0.17+15/bellsoft-jdk17.0.17+15-windows-amd64-full.zip"
                "win" if arch == "x32" -> "$ROOT/17.0.17+15/bellsoft-jdk17.0.17+15-windows-i586-full.zip"
                "linux" if arch == "x64" -> "$ROOT/17.0.17+15/bellsoft-jdk17.0.17+15-linux-amd64-full.tar.gz"
                "linux" if arch == "arm64" -> "$ROOT/17.0.17+15/bellsoft-jdk17.0.17+15-linux-aarch64.tar.gz"
                "mac" if arch == "x64" -> "$ROOT/17.0.17+15/bellsoft-jdk17.0.17+15-macos-amd64-full.tar.gz"
                "mac" if arch == "arm64" -> "$ROOT/17.0.17+15/bellsoft-jdk17.0.17+15-macos-aarch64-full.tar.gz"
                else -> null
            }
            21 -> when (os) {
                "win" if arch == "x64" -> "$ROOT/21.0.9+15/bellsoft-jdk21.0.9+15-windows-amd64-full.zip"
                "linux" if arch == "x64" -> "$ROOT/21.0.9+15/bellsoft-jdk21.0.9+15-linux-amd64-full.tar.gz"
                "linux" if arch == "arm64" -> "$ROOT/21.0.9+15/bellsoft-jdk21.0.9+15-linux-aarch64.tar.gz"
                "mac" if arch == "x64" -> "$ROOT/21.0.9+15/bellsoft-jdk21.0.9+15-macos-amd64-full.tar.gz"
                "mac" if arch == "arm64" -> "$ROOT/21.0.9+15/bellsoft-jdk21.0.9+15-macos-aarch64-full.tar.gz"
                else -> null
            }
            25 -> when (os) {
                "win" if arch == "x64" -> "$ROOT/25.0.3+11/bellsoft-jdk25.0.3+11-windows-amd64-full.zip"
                "linux" if arch == "x64" -> "$ROOT/25.0.3+11/bellsoft-jdk25.0.3+11-linux-amd64-full.tar.gz"
                "linux" if arch == "arm64" -> "$ROOT/25.0.3+11/bellsoft-jdk25.0.3+11-linux-aarch64.tar.gz"
                "mac" if arch == "x64" -> "$ROOT/25.0.3+11/bellsoft-jdk25.0.3+11-macos-amd64-full.tar.gz"
                "mac" if arch == "arm64" -> "$ROOT/25.0.3+11/bellsoft-jdk25.0.3+11-macos-aarch64-full.tar.gz"
                else -> null
            }
            else -> null
        }
    }

    private val PINS = mapOf(8 to "8u472+9", 17 to "17.0.17+15", 21 to "21.0.9+15", 25 to "25.0.3+11")
}

/**
 * Adoptium / Temurin GitHub-release URL for the given Java major.
 * Pinned to a known LTS-line build per major; bump these by hand
 * when a newer build is needed. GitHub releases are statically
 * served, no CloudFlare bot manager in front -- works from
 * regions where BellSoft's CDN returns 403.
 *
 * The `+` in the Temurin tag name is %2B-encoded so the URL stays
 * valid through every HTTP-client URL-parser variant. The filename
 * uses the underscored form (`21.0.5_11`) which is Adoptium's own
 * convention.
 *
 * Alpine is how Adoptium names its musl builds. They exist for aarch64 from 21 on
 * only: the older majors were never built for it.
 */
internal object AdoptiumSource : JdkSource {

    private data class Pin(val tag: String, val fileVersion: String)

    private val pins = mapOf(
        8 to Pin("8u442-b06", "8u442b06"),
        17 to Pin("17.0.13+11", "17.0.13_11"),
        21 to Pin("21.0.5+11", "21.0.5_11"),
        25 to Pin("25.0.3+9", "25.0.3_9"),
    )

    override fun url(major: Int, target: RuntimeTarget): String? {
        val pin = pins[major] ?: return null
        val os = target.os
        val arch = target.arch
        val musl = os == "linux" && target.linuxLibc == Libc.MUSL
        if (os == "linux" && target.linuxLibc == Libc.BIONIC) return null
        return when {
            os == "win" && arch == "x64" -> adoptium(major, pin, "x64", "windows", "zip")
            os == "mac" && arch == "x64" -> adoptium(major, pin, "x64", "mac", "tar.gz")
            os == "mac" && arch == "arm64" -> adoptium(major, pin, "aarch64", "mac", "tar.gz")
            musl && arch == "x64" -> adoptium(major, pin, "x64", "alpine-linux", "tar.gz")
            musl && arch == "arm64" && major >= 21 -> adoptium(major, pin, "aarch64", "alpine-linux", "tar.gz")
            os == "linux" && !musl && arch == "x64" -> adoptium(major, pin, "x64", "linux", "tar.gz")
            os == "linux" && !musl && arch == "arm64" -> adoptium(major, pin, "aarch64", "linux", "tar.gz")
            else -> null
        }
    }

    private fun adoptium(major: Int, pin: Pin, arch: String, os: String, ext: String): String {
        val tagEncoded = pin.tag.replace("+", "%2B")
        // Java 8 release tag prefix is `jdk` (no dash before the version),
        // 9+ uses `jdk-`. Mirrors Adoptium's own release naming.
        val tagPrefix = if (major == 8) "jdk" else "jdk-"
        return "https://github.com/adoptium/temurin${major}-binaries/releases/download/" +
            "$tagPrefix$tagEncoded/OpenJDK${major}U-jdk_${arch}_${os}_hotspot_${pin.fileVersion}.$ext"
    }
}
