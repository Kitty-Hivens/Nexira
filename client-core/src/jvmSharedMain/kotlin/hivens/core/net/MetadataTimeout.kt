package hivens.core.net

import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.plugins.timeout
import io.ktor.client.request.HttpRequestBuilder

/**
 * How long a metadata read may take: a listing, a version index, a feed, a
 * release page. Far tighter than the shared client's own timeout.
 *
 * That one is sized for a download, ten minutes, correctly, for a runtime or a
 * pack archive. Metadata is what a person is looking at while it runs, a picker
 * or a rail or an update check, so the same ceiling turns a stall into a spinner
 * that outlasts anyone's patience with no error, no log line and nothing to
 * retry. Past this a stall is an ordinary failure: it throws, it is written down,
 * and the screen offers the retry it already has.
 */
const val METADATA_TIMEOUT_MS: Long = 20_000L

/**
 * How long a download may go without a single byte before it is given up and asked
 * for again.
 *
 * A download has no bound on how long it runs as a whole: a slow line moves a large
 * file for as long as it takes. What marks a dead one is silence. Left to the shared
 * client's ten minutes, one host that stopped answering held a whole pack install
 * still for that long and then failed it.
 */
const val TRANSFER_STALL_MS: Long = 30_000L

/** Gives this request up after [stallMs] of silence, and not for taking long. For a download. */
fun HttpRequestBuilder.transferTimeout(stallMs: Long = TRANSFER_STALL_MS) {
    timeout {
        requestTimeoutMillis = HttpTimeoutConfig.INFINITE_TIMEOUT_MS
        socketTimeoutMillis = stallMs
    }
}

/** Bounds this request by [METADATA_TIMEOUT_MS]. For metadata, never for a download. */
fun HttpRequestBuilder.metadataTimeout() {
    timeout { requestTimeoutMillis = METADATA_TIMEOUT_MS }
}
