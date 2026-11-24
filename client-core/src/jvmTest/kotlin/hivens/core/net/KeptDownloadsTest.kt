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
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days

/** An install that does not finish, and the one after it, each in an instance directory of its own. */
class KeptDownloadsTest {

    private val root: Path = Files.createTempDirectory("kept")
    private val store: Path = root.resolve("downloads").resolve("kept")
    private val kept = KeptDownloads(store)
    private val ranges = mutableListOf<String?>()

    /** Addresses that fail, and those whose body is cut half way. */
    private val down = mutableSetOf<String>()
    private val cut = mutableSetOf<String>()

    private val a = ByteArray(100) { (it * 7 % 251).toByte() }
    private val b = ByteArray(100) { (it * 11 % 251).toByte() }

    private val engine = TransferEngine(
        http = HttpClient(
            MockEngine { request ->
                val url = request.url.toString()
                val range = request.headers[HttpHeaders.Range]
                synchronized(ranges) { ranges += "${url.last()} ${range ?: "whole"}" }
                val body = if (url == A) a else b
                val from = range?.removePrefix("bytes=")?.substringBefore('-')?.toInt() ?: 0
                val to = range?.substringAfter('-')?.toIntOrNull() ?: (body.size - 1)
                val slice = body.copyOfRange(from, minOf(to + 1, body.size))
                when (url) {
                    in down -> respond("down", HttpStatusCode.NotFound)
                    else -> respond(
                        ByteReadChannel(if (url in cut) slice.copyOf(slice.size / 2) else slice),
                        if (range == null) HttpStatusCode.OK else HttpStatusCode.PartialContent,
                        headersOf(
                            HttpHeaders.ContentLength to listOf(slice.size.toString()),
                            HttpHeaders.ContentRange to listOf("bytes $from-${from + slice.size - 1}/${body.size}"),
                        ),
                    )
                }
            },
        ).let { client -> HttpClientProvider { client } },
        gate = AdaptiveGate(initial = 1, min = 1, max = 1),
        blockSize = 16,
        parallelThreshold = 1_000,
        backoffMs = listOf(0L, 0L, 0L),
        kept = kept,
    )

    @AfterTest
    fun tearDown() {
        root.toFile().deleteRecursively()
    }

    private fun digest(bytes: ByteArray) = Digest(DigestAlgorithm.SHA1, DigestAlgorithm.SHA1.of(bytes))

    private fun instance(name: String): Path = root.resolve("instances").resolve(name).also { kept.watch(it) }

    private fun mod(dir: Path, url: String, bytes: ByteArray) = Transfer(url, dir.resolve("mods").resolve("${url.last()}.jar"), digest(bytes), bytes.size.toLong())

    private fun end(dir: Path, keep: Boolean): Int = kept.settle(dir, keep).also { dir.toFile().deleteRecursively() }

    private fun held(): List<String> = if (Files.isDirectory(store)) store.listDirectoryEntries().map { it.name }.sorted() else emptyList()

    @Test
    fun `what a failed install had downloaded is not downloaded again`() = runTest {
        down += B
        val first = instance("first")
        val _ = engine.fetch(mod(first, A, a))
        assertFailsWith<HttpStatusException> { engine.fetch(mod(first, B, b)) }
        assertEquals(1, end(first, keep = true))
        assertFalse(Files.exists(first))

        down.clear()
        ranges.clear()
        val second = instance("second")
        assertEquals(0L, engine.fetch(mod(second, A, a)), "nothing came off the network")
        val _ = engine.fetch(mod(second, B, b))

        assertContentEquals(a, Files.readAllBytes(second.resolve("mods/1.jar")))
        assertContentEquals(b, Files.readAllBytes(second.resolve("mods/2.jar")))
        assertEquals(listOf<String?>("2 whole"), ranges)
        end(second, keep = false)
        assertEquals(emptyList(), held(), "taken back and not left behind twice")
    }

    @Test
    fun `an install that fails twice keeps what both attempts had`() = runTest {
        val first = instance("first")
        val _ = engine.fetch(mod(first, A, a))
        end(first, keep = true)

        val second = instance("second")
        val _ = engine.fetch(mod(second, A, a))
        val _ = engine.fetch(mod(second, B, b))
        assertEquals(2, end(second, keep = true))

        ranges.clear()
        val third = instance("third")
        val _ = engine.fetch(mod(third, A, a))
        val _ = engine.fetch(mod(third, B, b))
        assertEquals(emptyList(), ranges)
    }

    @Test
    fun `a file that was cut half way goes on from there`() = runTest {
        cut += A
        val first = instance("first")
        assertFailsWith<Exception> { engine.fetch(mod(first, A, a)) }
        val have = Files.size(TransferStaging.partialOf(first.resolve("mods/1.jar")))
        assertTrue(have in 1..99, "part of it arrived: $have")
        assertEquals(0, end(first, keep = true), "no whole file")

        cut.clear()
        ranges.clear()
        val second = instance("second")
        val _ = engine.fetch(mod(second, A, a))
        assertContentEquals(a, Files.readAllBytes(second.resolve("mods/1.jar")))
        assertEquals(listOf<String?>("1 bytes=$have-"), ranges)
    }

    @Test
    fun `a file that was cut between blocks goes on with its journal`() = runTest {
        val blocks = TransferEngine(
            http = HttpClientProvider { error("nothing is fetched here") },
            kept = kept,
        )
        val first = instance("first")
        val dest = first.resolve("mods/1.jar")
        val partial = TransferStaging.partialOf(dest)
        blocks.claimKept(dest, digest(a))
        Files.createDirectories(partial.parent)
        Files.write(partial, a)
        JournalStore().write(partial, TransferJournal(A, 100, 16, digest(a).value, done = listOf(0, 1)))
        end(first, keep = true)

        val second = instance("second").resolve("mods/other-name.jar")
        blocks.claimKept(second, digest(a))
        val back = TransferStaging.partialOf(second)
        assertContentEquals(a, Files.readAllBytes(back))
        assertEquals(listOf(0, 1), JournalStore().read(back)?.done)
        assertEquals(emptyList(), held())
    }

    @Test
    fun `a kept file that is not what its name says is fetched again`() = runTest {
        Files.createDirectories(store)
        Files.write(store.resolve("sha1-${digest(a).value}"), b)
        val dir = instance("first")
        val _ = engine.fetch(mod(dir, A, a))
        assertContentEquals(a, Files.readAllBytes(dir.resolve("mods/1.jar")))
    }

    @Test
    fun `a directory nobody watches keeps and is given nothing`() = runTest {
        val first = instance("first")
        val _ = engine.fetch(mod(first, A, a))
        end(first, keep = true)
        val before = held()

        val installed = root.resolve("instances").resolve("installed")
        val _ = engine.fetch(mod(installed, A, a))
        assertEquals(before, held(), "an installed pack's update does not take it")
        assertEquals(0, kept.settle(installed, keep = true))
        assertTrue(Files.exists(installed.resolve("mods/1.jar")), "and is not emptied")
    }

    @Test
    fun `a download with no digest is not kept, and neither is one whose digest is no digest`() = runTest {
        val dir = instance("first")
        Files.createDirectories(store)
        val _ = engine.fetch(mod(dir, A, a).copy(expect = null))
        kept.claim(dir.resolve("x"), Digest(DigestAlgorithm.SHA1, "../escaped"))
        Files.write(dir.resolve("x"), a)
        assertEquals(0, end(dir, keep = true))
        assertEquals(emptyList(), held())
        assertFalse(Files.exists(store.resolveSibling("escaped")), "a manifest does not say where a file is kept")
    }

    @Test
    fun `what nobody came back for goes with time, and the oldest go past the size`() {
        Files.createDirectories(store)
        val now = Instant.parse("2026-11-24T12:00:00Z")
        fun put(name: String, size: Int, ageDays: Long) {
            val file = Files.write(store.resolve(name), ByteArray(size))
            Files.setLastModifiedTime(file, FileTime.from(now.minusSeconds(ageDays * 86_400)))
        }
        put("sha1-old", 10, ageDays = 8)
        put("sha1-first", 40, ageDays = 3)
        put("sha1-second", 40, ageDays = 2)
        put("sha1-third", 40, ageDays = 1)

        KeptDownloads(store, maxAge = 7.days, maxBytes = 1_000, clock = { now }).prune()
        assertEquals(listOf("sha1-first", "sha1-second", "sha1-third"), held(), "by age alone")

        KeptDownloads(store, maxAge = 7.days, maxBytes = 100, clock = { now }).prune()
        assertEquals(listOf("sha1-second", "sha1-third"), held())
    }

    private companion object {
        const val A = "https://host/1"
        const val B = "https://host/2"
    }
}
