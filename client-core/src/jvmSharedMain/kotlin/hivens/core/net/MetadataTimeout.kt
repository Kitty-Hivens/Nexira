package hivens.core.net

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

/** Bounds this request by [METADATA_TIMEOUT_MS]. For metadata, never for a download. */
fun HttpRequestBuilder.metadataTimeout() {
    timeout { requestTimeoutMillis = METADATA_TIMEOUT_MS }
}
