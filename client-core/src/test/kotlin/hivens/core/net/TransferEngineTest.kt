package hivens.core.net

import hivens.core.api.HttpClientProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TransferEngineTest {

    private val temps = mutableListOf<Path>()

    @AfterTest
    fun cleanup() = temps.forEach { it.toFile().deleteRecursively() }

    private fun tempDir(name: String) = Files.createTempDirectory(name).also { temps.add(it) }

    /**
     * A hundred bytes, distinct per position, so a file assembled out of order or
     * out of two versions is caught by content and not merely by length.
     */
    private val payload = ByteArray(100) { (it * 7 % 251).toByte() }
    private val payloadSha1 = Digest(DigestAlgorithm.SHA1, DigestAlgorithm.SHA1.of(payload))

    /**
     * A host with the range semantics a static server has, plus knobs for the
     * failures worth reproducing: a busy period, a forced status, a body that ends
     * short of its declared length, and no range support at all.
     *
     * [cutRangeAt] cuts the body of every request starting at that offset and only
     * those, which is what makes a multi-block test deterministic: the blocks
     * around the broken one behave normally.
     */
    private class Host(val body: ByteArray) {
        var ignoreRanges = false
        var forced: HttpStatusCode? = null
        var failNext = 0
        var truncateNext = 0
        var cutRangeAt: Long? = null
        val ranges = mutableListOf<String?>()
        var bodies = 0
    }

    private fun engineOf(hosts: Map<String, Host>) = MockEngine { req ->
        val host = hosts[req.url.toString()]
            ?: return@MockEngine respond("no such url", HttpStatusCode.NotFound)
        val rangeHeader = req.headers[HttpHeaders.Range]
        synchronized(host) { host.ranges += rangeHeader }
        host.forced?.let { return@MockEngine respond("forced", it) }
        if (host.failNext > 0) {
            host.failNext--
            return@MockEngine respond("busy", HttpStatusCode.ServiceUnavailable)
        }
        val range = rangeHeader?.removePrefix("bytes=")?.let { spec ->
            val from = spec.substringBefore('-').toLong()
            val to = spec.substringAfter('-').takeIf { it.isNotBlank() }?.toLong() ?: (host.body.size - 1L)
            from..to
        }
        if (range == null || host.ignoreRanges) {
            // No range in play, so the offset form of the cut never matches.
            return@MockEngine serve(host, host.body, HttpStatusCode.OK, null, host.takeTruncation(-1L, host.body.size))
        }
        if (range.first >= host.body.size) {
            return@MockEngine respond("", HttpStatusCode.RequestedRangeNotSatisfiable)
        }
        val last = minOf(range.last, host.body.size - 1L)
        val slice = host.body.copyOfRange(range.first.toInt(), last.toInt() + 1)
        serve(
            host,
            slice,
            HttpStatusCode.PartialContent,
            "bytes ${range.first}-$last/${host.body.size}",
            host.takeTruncation(range.first, slice.size),
        )
    }

    /**
     * Whether this response is the one to cut. The counter form is consumed here,
     * so a count of one cuts exactly one body; the offset form is not, so the same
     * block breaks on every attempt.
     *
     * A body of one byte is never a candidate: half of it is nothing, and spending
     * the counter on the plan's one-byte probe would leave every block intact and
     * quietly turn a cut-block test into a happy-path one.
     */
    private fun Host.takeTruncation(rangeStart: Long, size: Int): Boolean {
        if (size <= 1) return false
        cutRangeAt?.let { return it == rangeStart }
        if (truncateNext > 0) {
            truncateNext--
            return true
        }
        return false
    }

    /**
     * Answers with [bytes], or -- when [cut] -- with half of them while still
     * declaring the full length. That is what a cut transfer looks like from the
     * client side: the length was promised and the body stopped short of it.
     */
    private fun MockRequestHandleScope.serve(
        host: Host,
        bytes: ByteArray,
        status: HttpStatusCode,
        contentRange: String?,
        cut: Boolean,
    ): HttpResponseData {
        synchronized(host) { host.bodies++ }
        val sent = if (cut && bytes.size > 1) bytes.copyOfRange(0, bytes.size / 2) else bytes
        val headers = buildList {
            add(HttpHeaders.ContentLength to listOf(bytes.size.toString()))
            contentRange?.let { add(HttpHeaders.ContentRange to listOf(it)) }
        }
        return respond(ByteReadChannel(sent), status, headersOf(*headers.toTypedArray()))
    }

    /**
     * Blocks of sixteen bytes over a thirty-two byte threshold, so the hundred-byte
     * payload is a seven-block parallel transfer. The shipped defaults are
     * megabytes; the behaviour is the same at either scale and this keeps the
     * fixtures readable.
     */
    private fun engine(
        hosts: Map<String, Host>,
        blockSize: Int = 16,
        threshold: Long = 32L,
        blocksInFlight: Int = 4,
    ): TransferEngine {
        val client = HttpClient(engineOf(hosts))
        return TransferEngine(
            http = HttpClientProvider { client },
            gate = AdaptiveGate(initial = 4, min = 1, max = 4),
            blockSize = blockSize,
            parallelThreshold = threshold,
            backoffMs = listOf(0L, 0L, 0L),
            blocksInFlight = blocksInFlight,
        )
    }

    /** Single-request mode: a threshold no fixture reaches. */
    private fun streaming(hosts: Map<String, Host>) = engine(hosts, threshold = 1_000L)

    private fun partialOf(dest: Path): Path = dest.resolveSibling("${dest.fileName}.part")
    private fun journalOf(dest: Path): Path = dest.resolveSibling("${dest.fileName}.part.state")

    // ── whole-body transfers ──────────────────────────────────────────────────

    @Test
    fun `a file lands and verifies`() = runTest {
        val dir = tempDir("xfer-basic")
        val dest = dir.resolve("a.bin")
        val host = Host(payload)

        val moved = streaming(mapOf(URL to host)).fetch(Transfer(URL, dest, payloadSha1, payload.size.toLong()))

        assertContentEquals(payload, Files.readAllBytes(dest))
        assertEquals(payload.size.toLong(), moved)
    }

    @Test
    fun `a body that does not match its digest is never committed`() = runTest {
        val dir = tempDir("xfer-mismatch")
        val dest = dir.resolve("a.bin")
        val host = Host(payload)
        val wrong = Digest(DigestAlgorithm.SHA1, "0".repeat(40))

        val failed = runCatching {
            streaming(mapOf(URL to host)).fetch(Transfer(URL, dest, wrong, payload.size.toLong()))
        }

        assertTrue(failed.isFailure, "a mismatching body was accepted")
        assertFalse(Files.exists(dest), "bad bytes were committed to the destination")
        assertFalse(Files.exists(partialOf(dest)), "the bad partial was left behind")
        // Once, not once per attempt: a manifest pinning the wrong hash must not
        // cost a full re-download three times over.
        assertEquals(2, host.bodies, "the mismatch was retried the wrong number of times")
    }

    @Test
    fun `a prefix is continued when a digest can vouch for the result`() = runTest {
        val dir = tempDir("xfer-resume")
        val dest = dir.resolve("a.bin")
        Files.write(partialOf(dest), payload.copyOfRange(0, 40))
        val host = Host(payload)

        streaming(mapOf(URL to host)).fetch(Transfer(URL, dest, payloadSha1, payload.size.toLong()))

        assertContentEquals(payload, Files.readAllBytes(dest))
        assertEquals(listOf<String?>("bytes=40-"), host.ranges, "the transfer did not continue from the partial")
    }

    @Test
    fun `a prefix is thrown away when nothing could vouch for the result`() = runTest {
        val dir = tempDir("xfer-noverify")
        val dest = dir.resolve("a.bin")
        // Bytes from some earlier, different object. Appended, they would produce a
        // file of exactly the right length and entirely wrong content, and with no
        // digest pinned there is nothing downstream that would notice.
        Files.write(partialOf(dest), ByteArray(40) { 0xAB.toByte() })
        val host = Host(payload)

        streaming(mapOf(URL to host)).fetch(Transfer(URL, dest, expect = null, size = payload.size.toLong()))

        assertContentEquals(payload, Files.readAllBytes(dest))
        assertEquals(listOf<String?>(null), host.ranges, "an unverifiable prefix was resumed")
    }

    @Test
    fun `a partial past the end of the object is dropped instead of failing forever`() = runTest {
        val dir = tempDir("xfer-416")
        val dest = dir.resolve("a.bin")
        // A transfer that reached the last byte and never got committed: the offset
        // is at the end, so the host has nothing left to send and answers 416 --
        // and goes on answering it for as long as the partial decides the offset.
        Files.write(partialOf(dest), payload + "TRAILING".toByteArray())
        val host = Host(payload)

        streaming(mapOf(URL to host)).fetch(Transfer(URL, dest, payloadSha1, payload.size.toLong()))

        assertContentEquals(payload, Files.readAllBytes(dest))
        assertFalse(Files.exists(partialOf(dest)))
    }

    @Test
    fun `a host that ignores ranges restarts the transfer instead of appending`() = runTest {
        val dir = tempDir("xfer-norange")
        val dest = dir.resolve("a.bin")
        Files.write(partialOf(dest), payload.copyOfRange(0, 40))
        val host = Host(payload).apply { ignoreRanges = true }

        streaming(mapOf(URL to host)).fetch(Transfer(URL, dest, payloadSha1, payload.size.toLong()))

        assertContentEquals(payload, Files.readAllBytes(dest))
    }

    @Test
    fun `a body that ends early is retried`() = runTest {
        val dir = tempDir("xfer-cut")
        val dest = dir.resolve("a.bin")
        val host = Host(payload).apply { truncateNext = 1 }

        streaming(mapOf(URL to host)).fetch(Transfer(URL, dest, payloadSha1, payload.size.toLong()))

        assertContentEquals(payload, Files.readAllBytes(dest))
        assertEquals(2, host.bodies, "the cut body was not retried exactly once")
    }

    // ── blocked transfers ─────────────────────────────────────────────────────

    @Test
    fun `a large file is fetched as blocks and assembled correctly`() = runTest {
        val dir = tempDir("xfer-blocks")
        val dest = dir.resolve("big.bin")
        val host = Host(payload)

        engine(mapOf(URL to host)).fetch(Transfer(URL, dest, payloadSha1, payload.size.toLong()))

        assertContentEquals(payload, Files.readAllBytes(dest), "the blocks were assembled wrong")
        assertEquals(1 + 7, host.ranges.size, "unexpected request count for a seven-block object")
        assertEquals("bytes=0-0", host.ranges.first(), "the plan was not opened with a probe")
        assertTrue(host.ranges.all { it != null }, "a block was fetched without a range")
        assertFalse(Files.exists(journalOf(dest)), "the journal outlived the transfer")
    }

    @Test
    fun `a cut block costs only itself`() = runTest {
        val dir = tempDir("xfer-block-cut")
        val dest = dir.resolve("big.bin")
        val host = Host(payload).apply { truncateNext = 1 }

        engine(mapOf(URL to host), blocksInFlight = 1)
            .fetch(Transfer(URL, dest, payloadSha1, payload.size.toLong()))

        assertContentEquals(payload, Files.readAllBytes(dest))
        // Probe, seven blocks, and one repeat of the block that was cut.
        assertEquals(1 + 7 + 1, host.ranges.size, "a cut block cost more than itself")
    }

    @Test
    fun `a transfer that dies resumes from its journal and asks only for the rest`() = runTest {
        val dir = tempDir("xfer-journal")
        val dest = dir.resolve("big.bin")
        val transfer = Transfer(URL, dest, payloadSha1, payload.size.toLong())

        // The fourth block breaks on every attempt; the three before it land.
        val dying = Host(payload).apply { cutRangeAt = 48L }
        val died = runCatching { engine(mapOf(URL to dying), blocksInFlight = 1).fetch(transfer) }
        assertTrue(died.isFailure, "the transfer was expected to fail")
        assertTrue(Files.exists(journalOf(dest)), "nothing was written down to resume from")
        assertTrue(Files.exists(partialOf(dest)), "the partial was discarded")

        // A fresh engine, the way a relaunch builds one, against a working host.
        val healthy = Host(payload)
        engine(mapOf(URL to healthy), blocksInFlight = 1).fetch(transfer)

        assertContentEquals(payload, Files.readAllBytes(dest))
        assertEquals(
            4, healthy.ranges.size,
            "the second run should have fetched the four missing blocks, asked for ${healthy.ranges}",
        )
        assertFalse(healthy.ranges.contains("bytes=0-0"), "a journal that applies does not need a fresh probe")
    }

    @Test
    fun `a journal describing a different object is discarded`() = runTest {
        val dir = tempDir("xfer-stale-journal")
        val dest = dir.resolve("big.bin")
        val other = ByteArray(100) { 0x5A }
        val otherSha1 = Digest(DigestAlgorithm.SHA1, DigestAlgorithm.SHA1.of(other))

        // A partial plus journal left by a build where this path held other bytes.
        val stale = Host(other).apply { cutRangeAt = 48L }
        runCatching {
            engine(mapOf(URL to stale), blocksInFlight = 1).fetch(Transfer(URL, dest, otherSha1, other.size.toLong()))
        }
        assertTrue(Files.exists(journalOf(dest)), "the fixture did not leave a journal to go stale")

        engine(mapOf(URL to Host(payload)), blocksInFlight = 1)
            .fetch(Transfer(URL, dest, payloadSha1, payload.size.toLong()))

        assertContentEquals(payload, Files.readAllBytes(dest), "bytes from two versions were mixed")
    }

    // ── failure handling ──────────────────────────────────────────────────────

    @Test
    fun `a missing object is not retried`() = runTest {
        val dir = tempDir("xfer-404")
        val dest = dir.resolve("a.bin")
        val host = Host(payload).apply { forced = HttpStatusCode.NotFound }

        val failed = runCatching {
            streaming(mapOf(URL to host)).fetch(Transfer(URL, dest, payloadSha1, payload.size.toLong()))
        }

        assertTrue(failed.isFailure)
        assertEquals(1, host.ranges.size, "a 404 was asked for again, which is backoff spent on a certainty")
    }

    @Test
    fun `a busy host is retried`() = runTest {
        val dir = tempDir("xfer-503")
        val dest = dir.resolve("a.bin")
        val host = Host(payload).apply { failNext = 2 }

        streaming(mapOf(URL to host)).fetch(Transfer(URL, dest, payloadSha1, payload.size.toLong()))

        assertContentEquals(payload, Files.readAllBytes(dest))
        assertEquals(3, host.ranges.size)
    }

    @Test
    fun `a mirror is tried only after the first source has spent its retries`() = runTest {
        val dir = tempDir("xfer-mirror")
        val dest = dir.resolve("a.bin")
        val primary = Host(payload).apply { forced = HttpStatusCode.ServiceUnavailable }
        val mirror = Host(payload)

        streaming(mapOf(URL to primary, MIRROR to mirror)).fetch(
            Transfer(URL, dest, payloadSha1, payload.size.toLong(), mirrors = listOf(MIRROR))
        )

        assertContentEquals(payload, Files.readAllBytes(dest))
        assertEquals(3, primary.ranges.size, "the primary was abandoned before its retries were spent")
        assertEquals(1, mirror.ranges.size)
    }

    // ── sets and skipping ─────────────────────────────────────────────────────

    @Test
    fun `a set is fetched and reported to completion`() = runTest {
        val dir = tempDir("xfer-set")
        val hosts = HashMap<String, Host>()
        val transfers = (1..5).map { i ->
            val url = "$URL/$i"
            hosts[url] = Host(payload)
            Transfer(url, dir.resolve("f$i.bin"), payloadSha1, payload.size.toLong())
        }
        var last: TransferProgress? = null

        val moved = streaming(hosts).fetchAll(transfers) { last = it }

        assertEquals(5 * payload.size.toLong(), moved)
        transfers.forEach { assertContentEquals(payload, Files.readAllBytes(it.dest)) }
        assertEquals(5, last?.filesDone, "the final report did not account for every file")
        assertEquals(5 * payload.size.toLong(), last?.done)
    }

    @Test
    fun `a file that is already right is left alone`() = runTest {
        val dir = tempDir("xfer-skip")
        val dest = dir.resolve("a.bin")
        Files.write(dest, payload)
        val host = Host(payload)

        val moved = streaming(mapOf(URL to host)).fetch(Transfer(URL, dest, payloadSha1, payload.size.toLong()))

        assertEquals(0L, moved)
        assertEquals(0, host.ranges.size, "an intact file was fetched again")
    }

    @Test
    fun `skipping by size does not hash the file`() = runTest {
        val dir = tempDir("xfer-skip-size")
        val dest = dir.resolve("a.bin")
        // Right length, wrong content: the trade a content-addressed store makes to
        // avoid re-hashing thousands of objects on every launch.
        Files.write(dest, ByteArray(payload.size) { 0x11 })
        val host = Host(payload)

        val moved = streaming(mapOf(URL to host)).fetch(
            Transfer(URL, dest, payloadSha1, payload.size.toLong(), skip = SkipIfPresent.BySize)
        )

        assertEquals(0L, moved)
        assertEquals(0, host.ranges.size)
    }

    private companion object {
        const val URL = "https://host.test/object"
        const val MIRROR = "https://mirror.test/object"
    }
}
