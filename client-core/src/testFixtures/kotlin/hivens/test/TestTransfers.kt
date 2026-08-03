package hivens.test

import hivens.core.api.HttpClientProvider
import hivens.core.net.AdaptiveGate
import hivens.core.net.TransferEngine

/**
 * A [TransferEngine] over [provider] with the waiting taken out: zero backoff, so
 * a test that exercises a retry does not spend thirteen seconds of wall clock on
 * the pauses between attempts, and a small fixed permit pool so request counts are
 * not at the mercy of the growth controller.
 *
 * The engine's own behaviour is covered in `client-core`; here it is a dependency
 * the services under test need, not the subject.
 */
fun testTransferEngine(provider: HttpClientProvider): TransferEngine = TransferEngine(
    http = provider,
    gate = AdaptiveGate(initial = 4, min = 1, max = 4),
    backoffMs = listOf(0L, 0L, 0L),
)
