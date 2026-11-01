package dev.hivens.nexira.host

import hivens.core.api.interfaces.IJavaManager
import hivens.launcher.runtime.AndroidJdkCatalog
import hivens.launcher.runtime.AndroidJdkInstaller
import java.io.IOException
import java.nio.file.Path

/**
 * The managed Java of a phone: a pinned build for the majors the catalog lists, fetched
 * on first use like the desktop's runtimes are.
 *
 * The embedded VM is loaded from a directory and never run as a program, so the answer
 * to "where is Java" is the runtime's home, [homeFor], and [getJavaPathForMajor] only
 * names the `bin/java` that a home would have for the callers that want a path.
 */
class AndroidJavaManager(
    private val installer: AndroidJdkInstaller,
    private val catalog: AndroidJdkCatalog = AndroidJdkCatalog.PINNED,
) : IJavaManager {

    /** The home of the runtime for [major], provisioned if it is not there, or null when no build is pinned for it. */
    suspend fun homeFor(major: Int, onProgress: (String) -> Unit = {}): Path? {
        val release = catalog.forMajor(major) ?: return null
        return installer.ensure(release, onProgress)
    }

    override suspend fun getJavaPath(version: String): Path = getJavaPathForMajor(detectJavaVersion(version))

    override suspend fun getJavaPathForMajor(javaMajor: Int, onProgress: (String) -> Unit): Path {
        val home = homeFor(javaMajor, onProgress) ?: throw IOException("Java $javaMajor is not available on this device")
        return home.resolve("bin").resolve("java")
    }
}
