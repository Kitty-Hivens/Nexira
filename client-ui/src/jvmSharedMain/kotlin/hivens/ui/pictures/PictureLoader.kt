package hivens.ui.pictures

import coil3.ComponentRegistry
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.annotation.ExperimentalCoilApi
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.network.CacheStrategy
import coil3.network.ConnectivityChecker
import coil3.network.DeDupeConcurrentRequestStrategy
import coil3.network.NetworkRequest
import coil3.network.NetworkResponse
import coil3.network.cachecontrol.CacheControlCacheStrategy
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.Options
import coil3.getExtra
import okhttp3.Call
import java.nio.file.Path

/** The most the picture cache keeps on disk before it evicts the least recently used. */
private const val DISK_CACHE_BYTES = 256L * 1024 * 1024

/**
 * The loader every picture off the network goes through.
 *
 * A picture kept on disk is drawn at once, whatever its age and whether or not its server
 * answers, and one its server may have changed is asked about behind it ([rechecks]). The
 * new picture is drawn the next time it is asked for.
 *
 * [directory] is in the launcher's own data, not the default under the system temp dir:
 * that one is a tmpfs on many Linux machines, emptied on every boot, so every icon and
 * banner was downloaded again each day, and it is shared with any other program built on
 * the same library.
 *
 * Two requests for one address at the same time download it once, since a picture fetched
 * ahead of being drawn can be drawn before it lands.
 */
@OptIn(ExperimentalCoilApi::class)
internal fun pictureLoader(
    context: PlatformContext,
    directory: Path,
    calls: Call.Factory,
    rechecks: PictureRechecks,
    decoders: ComponentRegistry.Builder.() -> Unit = {},
): ImageLoader {
    val loader = ImageLoader.Builder(context)
        .diskCache {
            DiskCache.Builder()
                .directory(directory.toFile())
                .maxSizeBytes(DISK_CACHE_BYTES)
                .build()
        }
        .components {
            val oneDownload = DeDupeConcurrentRequestStrategy()
            add(
                OkHttpNetworkFetcherFactory(
                    callFactory               = { calls },
                    cacheStrategy             = { KeptFirst(rechecks) },
                    connectivityChecker       = ::ConnectivityChecker,
                    concurrentRequestStrategy = { oneDownload },
                ),
            )
            decoders()
        }
        .build()
    rechecks.loader = loader
    return loader
}

/**
 * Draws what is kept and has [rechecks] ask about it when its own headers say it may have
 * changed. Art on the mirror keeps one address when the picture behind it changes, so
 * what is kept has to be asked about, and nobody should wait for the answer.
 *
 * An answer that says the picture is gone replaces it. One that says the server is in
 * trouble does not.
 */
@OptIn(ExperimentalCoilApi::class)
internal class KeptFirst(
    private val rechecks: PictureRechecks,
    private val headers: CacheStrategy = CacheControlCacheStrategy(),
) : CacheStrategy {

    override suspend fun read(cacheResponse: NetworkResponse, networkRequest: NetworkRequest, options: Options): CacheStrategy.ReadResult {
        val verdict = headers.read(cacheResponse, networkRequest, options)
        val fresh = verdict.response != null
        if (fresh || options.getExtra(Rechecking) || !cacheResponse.holdsPicture) return verdict
        rechecks.ask(networkRequest.url, options.context)
        return CacheStrategy.ReadResult(cacheResponse)
    }

    override suspend fun write(
        cacheResponse: NetworkResponse?,
        networkRequest: NetworkRequest,
        networkResponse: NetworkResponse,
        options: Options,
    ): CacheStrategy.WriteResult {
        if (cacheResponse != null && cacheResponse.holdsPicture && !networkResponse.holdsPicture && networkResponse.code !in GONE) {
            return CacheStrategy.WriteResult(cacheResponse.copy(body = null))
        }
        val result = headers.write(cacheResponse, networkRequest, networkResponse, options)
        if (options.getExtra(Rechecking) && networkResponse.code != NOT_MODIFIED) rechecks.changed(networkRequest.url)
        return result
    }

    /** A picture rechecked without a change is kept under the 304 that said so. */
    private val NetworkResponse.holdsPicture: Boolean get() = code in 200 until 300 || code == NOT_MODIFIED

    private companion object {
        const val NOT_MODIFIED = 304
        val GONE = setOf(404, 410)
    }
}
