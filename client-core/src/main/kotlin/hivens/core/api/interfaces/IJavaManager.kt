package hivens.core.api.interfaces

import java.nio.file.Path

/**
 * Contract for the managed-Java-runtime service.
 *
 * The launcher ships its own per-MC-version BellSoft Liberica JDKs into the
 * data directory rather than relying on whatever the user has installed
 * system-wide. This interface is the abstraction other launch-pipeline
 * components depend on, so tests can substitute a fake without spinning
 * up the actual download path.
 */
interface IJavaManager {

    /**
     * Returns the absolute path to the `java` executable suitable for the
     * given Minecraft version. Triggers a download into the runtimes
     * directory if the required Liberica build is not already on disk.
     */
    suspend fun getJavaPath(version: String): Path

    /**
     * The Java major a given Minecraft version requires (8 / 17 / 21). Drives
     * both which JDK [getJavaPath] provisions and launch-arg choices that
     * depend on the JVM generation (e.g. `-noverify`, deprecated since 13).
     */
    fun detectJavaVersion(mcVersion: String): Int = when {
        mcVersion.startsWith("1.21") || mcVersion.startsWith("1.20.5") || mcVersion.startsWith("1.20.6") -> 21
        mcVersion.startsWith("1.17") || mcVersion.startsWith("1.18") ||
            mcVersion.startsWith("1.19") || mcVersion.startsWith("1.20") -> 17
        else -> 8
    }
}
