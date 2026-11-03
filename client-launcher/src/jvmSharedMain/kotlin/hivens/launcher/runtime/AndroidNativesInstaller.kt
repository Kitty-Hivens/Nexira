package hivens.launcher.runtime

import hivens.core.net.Digest
import hivens.core.net.DigestAlgorithm
import hivens.core.net.Transfer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * Gives a phone the LWJGL natives for the release a game carries, fetched once and kept.
 *
 * Each archive is held by the transfer engine to the digest pinned in the [catalog]: one already on disk with that
 * digest is not fetched again, and one that is there with another is replaced. The archives stay archives, since
 * unpacking them into an instance's natives folder is the environment preparer's business, the same as for the jars
 * Mojang names.
 *
 * [fetch] is the transfer engine's `fetch`, passed as a function so the installer can be tested without a network.
 */
class AndroidNativesInstaller(
    private val dir: Path,
    private val catalog: AndroidNativesCatalog,
    private val fetch: suspend (Transfer, (done: Long, total: Long) -> Unit) -> Unit,
) : HostNativeSource {
    private val log = LoggerFactory.getLogger(AndroidNativesInstaller::class.java)

    override suspend fun natives(lwjglVersion: String, onProgress: (String) -> Unit): List<Path> = withContext(Dispatchers.IO) {
        val release = catalog.forLwjgl(lwjglVersion)
        if (release == null) {
            log.warn("no natives are pinned for LWJGL {}, the ones this phone has are for {}", lwjglVersion, catalog.versions)
            return@withContext emptyList()
        }
        release.assets.map { asset ->
            val dest = dir.resolve("lwjgl-${release.lwjglVersion}").resolve(asset.name)
            onProgress("Fetching ${asset.name}...")
            fetch(Transfer(url = asset.url, dest = dest, expect = Digest(DigestAlgorithm.SHA256, asset.sha256))) { _, _ -> }
            dest
        }
    }
}
