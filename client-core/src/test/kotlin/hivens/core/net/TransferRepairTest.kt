package hivens.core.net

import hivens.core.api.HttpClientProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
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
import kotlin.test.assertTrue

/**
 * Repair: what it costs to put a damaged file right, and what it refuses to
 * pretend it can do.
 */
class TransferRepairTest {

    private val temps = mutableListOf<Path>()

    @AfterTest
    fun cleanup() = temps.forEach { it.toFile().deleteRecursively() }

    private fun tempDir(name: String) = Files.createTempDirectory(name).also { temps.add(it) }

    private val payload = ByteArray(100) { (it * 7 % 251).toByte() }
    private val payloadSha1 = Digest(DigestAlgorithm.SHA1, DigestAlgorithm.SHA1.of(payload))

    /** Every ranged request this host answered, so a test can price the repair. */
    private val served = mutableListOf<String?>()

    private fun engine(blockSize: Int = 16, threshold: Long = 32L): TransferEngine {
        val client = HttpClient(
            MockEngine { req ->
                val rangeHeader = req.headers[HttpHeaders.Range]
                synchronized(served) { served += rangeHeader }
                val range = rangeHeader?.removePrefix("bytes=")?.let { spec ->
                    val from = spec.substringBefore('-').toLong()
                    val to = spec.substringAfter('-').takeIf { it.isNotBlank() }?.toLong() ?: (payload.size - 1L)
                    from..to
                }
                if (range == null) {
                    respond(
                        ByteReadChannel(payload),
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentLength to listOf(payload.size.toString())),
                    )
                } else if (range.first >= payload.size) {
                    respond("", HttpStatusCode.RequestedRangeNotSatisfiable)
                } else {
                    val last = minOf(range.last, payload.size - 1L)
                    val slice = payload.copyOfRange(range.first.toInt(), last.toInt() + 1)
                    respond(
                        ByteReadChannel(slice),
                        HttpStatusCode.PartialContent,
                        headersOf(
                            HttpHeaders.ContentLength to listOf(slice.size.toString()),
                            HttpHeaders.ContentRange to listOf("bytes ${range.first}-$last/${payload.size}"),
                        ),
                    )
                }
            }
        )
        return TransferEngine(
            http = HttpClientProvider { client },
            gate = AdaptiveGate(initial = 4, min = 1, max = 4),
            blockSize = blockSize,
            parallelThreshold = threshold,
            backoffMs = listOf(0L, 0L, 0L),
            blocksInFlight = 1,
        )
    }

    private fun transferTo(dest: Path) = Transfer(URL, dest, payloadSha1, payload.size.toLong())

    private fun blockMapOf(dest: Path): Path =
        dest.parent.resolve(".nexira-blocks").resolve("${dest.fileName}.blocks")

    @Test
    fun `a completed transfer leaves a block map behind`() = runTest {
        val dir = tempDir("repair-map")
        val dest = dir.resolve("big.bin")

        engine().fetch(transferTo(dest))

        assertTrue(Files.exists(blockMapOf(dest)), "no block map was written for a blocked transfer")
    }

    @Test
    fun `a small file gets no block map`() = runTest {
        val dir = tempDir("repair-small")
        val dest = dir.resolve("small.bin")

        // Threshold above the payload, so the transfer is one request and the map
        // would buy nothing a whole-file rehash does not already give.
        engine(threshold = 1_000L).fetch(transferTo(dest))

        assertTrue(Files.notExists(blockMapOf(dest)), "a small file was given bookkeeping it cannot use")
    }

    @Test
    fun `an intact file is checked and left alone`() = runTest {
        val dir = tempDir("repair-intact")
        val dest = dir.resolve("big.bin")
        engine().fetch(transferTo(dest))
        served.clear()

        val report = engine().verifyAndRepair(listOf(transferTo(dest)))

        assertEquals(1, report.checked)
        assertEquals(1, report.intact)
        assertEquals(0L, report.bytesFetched)
        assertEquals(emptyList<String?>(), served, "an intact file caused network traffic")
    }

    @Test
    fun `a damaged block is the only thing refetched`() = runTest {
        val dir = tempDir("repair-block")
        val dest = dir.resolve("big.bin")
        engine().fetch(transferTo(dest))
        served.clear()

        // One byte flipped in the fourth 16-byte block.
        val damaged = Files.readAllBytes(dest)
        damaged[50] = (damaged[50] + 1).toByte()
        Files.write(dest, damaged)

        val report = engine().verifyAndRepair(listOf(transferTo(dest)))

        assertContentEquals(payload, Files.readAllBytes(dest), "the file was not put right")
        assertEquals(listOf("big.bin"), report.repaired)
        assertEquals(16L, report.bytesFetched, "the repair did not cost exactly one block")
        assertEquals(listOf<String?>("bytes=48-63"), served, "the repair asked for the wrong bytes")
    }

    @Test
    fun `a file with no map is refetched whole`() = runTest {
        val dir = tempDir("repair-nomap")
        val dest = dir.resolve("big.bin")
        Files.write(dest, ByteArray(payload.size) { 0x22 })
        served.clear()

        val report = engine().verifyAndRepair(listOf(transferTo(dest)))

        assertContentEquals(payload, Files.readAllBytes(dest))
        assertEquals(listOf("big.bin"), report.repaired)
        assertEquals(payload.size.toLong(), report.bytesFetched, "a repair with no evidence cost more than the file")
    }

    @Test
    fun `a missing file is fetched and counted as repaired`() = runTest {
        val dir = tempDir("repair-missing")
        val dest = dir.resolve("big.bin")

        val report = engine().verifyAndRepair(listOf(transferTo(dest)))

        assertContentEquals(payload, Files.readAllBytes(dest))
        assertEquals(listOf("big.bin"), report.repaired)
    }

    @Test
    fun `a map from an older version of the file is not trusted`() = runTest {
        val dir = tempDir("repair-stale-map")
        val dest = dir.resolve("big.bin")
        engine().fetch(transferTo(dest))

        // The pack republishes this path with other bytes, so the stored map -- taken
        // under the previous digest -- has nothing to say about what should be there.
        val other = ByteArray(100) { 0x5A }
        Files.write(dest, other)
        val otherSha1 = Digest(DigestAlgorithm.SHA1, DigestAlgorithm.SHA1.of(other))
        served.clear()

        val report = engine().verifyAndRepair(
            listOf(Transfer(URL, dest, otherSha1, other.size.toLong()))
        )

        // The file on disk IS the new content, and the new digest says so, so nothing
        // is fetched -- but the verdict came from hashing the file, not from a map
        // that describes a different one.
        assertEquals(1, report.intact)
        assertEquals(emptyList<String?>(), served)
    }

    @Test
    fun `a file the host cannot serve is reported rather than left half-repaired`() = runTest {
        val dir = tempDir("repair-fail")
        val dest = dir.resolve("big.bin")
        Files.write(dest, ByteArray(payload.size) { 0x33 })
        val client = HttpClient(MockEngine { respond("gone", HttpStatusCode.NotFound) })
        val broken = TransferEngine(
            http = HttpClientProvider { client },
            backoffMs = listOf(0L, 0L, 0L),
        )

        val report = broken.verifyAndRepair(listOf(transferTo(dest)))

        assertEquals(0, report.intact)
        assertEquals(emptyList<String>(), report.repaired)
        assertTrue(report.failed.containsKey("big.bin"), "a failed repair was not reported: ${report.failed}")
    }

    private companion object {
        const val URL = "https://host.test/object"
    }
}
