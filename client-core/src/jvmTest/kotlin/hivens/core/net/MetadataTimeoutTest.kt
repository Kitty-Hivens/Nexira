package hivens.core.net

import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.plugins.HttpTimeoutConfig
import io.ktor.client.request.HttpRequestBuilder
import kotlin.test.Test
import kotlin.test.assertEquals

class MetadataTimeoutTest {

    @Test
    fun `a metadata read carries the tight bound, not the download one`() {
        val request = HttpRequestBuilder().apply { metadataTimeout() }

        assertEquals(METADATA_TIMEOUT_MS, request.getCapabilityOrNull(HttpTimeoutCapability)?.requestTimeoutMillis)
    }

    @Test
    fun `a download is bounded by silence and not by how long it runs`() {
        val timeout = HttpRequestBuilder().apply { transferTimeout() }.getCapabilityOrNull(HttpTimeoutCapability)

        assertEquals(TRANSFER_STALL_MS, timeout?.socketTimeoutMillis)
        assertEquals(HttpTimeoutConfig.INFINITE_TIMEOUT_MS, timeout?.requestTimeoutMillis)
    }
}
