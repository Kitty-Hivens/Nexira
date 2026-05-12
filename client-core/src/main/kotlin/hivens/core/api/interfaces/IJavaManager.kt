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
}
