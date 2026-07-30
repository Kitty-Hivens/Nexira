package hivens.core.net

import hivens.core.api.HttpClientProvider
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.EOFException
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlin.time.Duration.Companion.milliseconds

/**
 * The one place bytes come off the network and land on disk.
 *
 * Before this there were eleven such places, each with a different subset of
 * retry, resume, integrity and progress: eight could not retry a cut transfer at
 * all, nine restarted a broken one from zero, three verified nothing. All of them
 * were reachable from a single pack install, so the weakest of them decided what
 * the player got.
 *
 * What a transfer gets here:
 *
 *  - **Retry** per unit of work -- one block, or one whole small file -- so a
 *    reset costs that unit rather than the file, and never the job.
 *  - **Resume** across attempts and across runs. A large transfer is a set of
 *    blocks with a journal beside it, so a killed process costs only what was in
 *    flight, and blocks may land in any order.
 *  - **Parallel blocks** above [parallelThreshold], because a resource pack, a
 *    shader pack or a JDK is one object, and per-file concurrency does nothing
 *    for one object.
 *  - **Mirror fallback** after the retries, not instead of them.
 *  - **Verification** against whatever digest the caller pinned, dropping bad
 *    bytes instead of committing them.
 *  - **Adaptive concurrency** that reacts to errors before it reacts to speed
 *    (see [AdaptiveGate]).
 *
 * One permit is one in-flight request, never one file. A transfer therefore never
 * holds a permit while waiting for another, which is what keeps a blocked
 * transfer inside a large set from deadlocking against itself.
 */
class TransferEngine(
    private val http: HttpClientProvider,
    private val gate: AdaptiveGate = AdaptiveGate(),
    private val journals: JournalStore = JournalStore(),
    private val blockSize: Int = DEFAULT_BLOCK_SIZE,
    private val parallelThreshold: Long = DEFAULT_PARALLEL_THRESHOLD,
    private val attempts: Int = DEFAULT_ATTEMPTS,
    private val backoffMs: List<Long> = DEFAULT_BACKOFF_MS,
    /**
     * Blocks of one object in flight at once. Not the concurrency limit -- the
     * gate is that -- this keeps one large object from holding every permit while
     * the small files behind it wait.
     */
    private val blocksInFlight: Int = DEFAULT_BLOCKS_IN_FLIGHT,
) {
    private val log = LoggerFactory.getLogger(TransferEngine::class.java)

    /**
     * One transfer per destination at a time, process-wide.
     *
     * The shared runtime roots get provisioned from more than one place at once --
     * a launch and an import can want the same library -- and two writers on one
     * journal would each mark blocks the other is still writing. The previous
     * answer to that was a unique temp file per writer, which avoids the collision
     * by giving up resume.
     *
     * Reference-counted so the map does not accumulate an entry per file for the
     * life of the process: a vanilla runtime alone is thousands of destinations,
     * and none of them is interesting once its transfer is done. Striping instead
     * would bound the map too, at the cost of an unrelated transfer waiting behind
     * a multi-minute one that happened to hash to the same stripe.
     */
    private val destLocks = HashMap<Path, DestLock>()

    private class DestLock {
        val mutex = Mutex()
        var users = 0
    }

    private suspend fun <T> withDestLock(dest: Path, block: suspend () -> T): T {
        val key = dest.normalize()
        val lock = synchronized(destLocks) { destLocks.getOrPut(key) { DestLock() }.also { it.users++ } }
        try {
            return lock.mutex.withLock { block() }
        } finally {
            synchronized(destLocks) {
                if (--lock.users == 0) destLocks.remove(key, lock)
            }
        }
    }

    /**
     * Fetch [t] to its destination. Returns the bytes actually pulled from the
     * network, which is zero when the file was already right.
     */
    suspend fun fetch(t: Transfer, onProgress: (done: Long, total: Long) -> Unit = { _, _ -> }): Long =
        withContext(Dispatchers.IO) {
            withDestLock(t.dest) {
                if (alreadySatisfied(t)) {
                    val size = t.size.coerceAtLeast(0L)
                    onProgress(size, size)
                    return@withDestLock 0L
                }
                t.dest.parent?.let { Files.createDirectories(it) }
                fetchFromAnySource(t, onProgress)
            }
        }

    /**
     * Fetch every transfer, letting the gate decide how many requests run at once.
     * Progress is aggregated over the set: bytes for the bar, file count and
     * current name for the text.
     *
     * Everything is launched together rather than in stages -- a small file behind
     * a large one has no reason to wait for it -- and the gate bounds the actual
     * concurrency.
     */
    suspend fun fetchAll(
        transfers: List<Transfer>,
        onProgress: (TransferProgress) -> Unit = {},
    ): Long = coroutineScope {
        if (transfers.isEmpty()) return@coroutineScope 0L
        val tracker = SetProgress(transfers.sumOf { it.size.coerceAtLeast(0L) }, transfers.size, onProgress)
        transfers.map { t ->
            async(Dispatchers.IO) {
                tracker.starting(t)
                val moved = fetch(t) { done, _ -> tracker.fileAt(t, done) }
                tracker.finished(t)
                moved
            }
        }.awaitAll().sum()
    }

    /**
     * Walks the sources in order, each with the full retry budget before the next
     * is tried. A transfer one attempt from finishing must not be sent to another
     * host to start over, which is what fallback-instead-of-retry does.
     *
     * A digest mismatch is retried once per source. A body that arrives whole but
     * wrong is usually a cache or a proxy answering for the origin and the next
     * request gets the real thing, but a manifest pinning the wrong hash would
     * otherwise cost a full re-download per attempt -- so once is the allowance.
     */
    private suspend fun fetchFromAnySource(t: Transfer, onProgress: (Long, Long) -> Unit): Long {
        val partial = partialOf(t.dest)
        var lastError: Throwable? = null
        var fetched = 0L
        for (url in t.sources) {
            var mismatchRetried = false
            while (true) {
                try {
                    fetched += runAttempts(t, url, partial, onProgress)
                    val expect = t.expect
                    if (expect != null) {
                        val actual = expect.algorithm.of(partial)
                        if (!expect.matches(actual)) {
                            dropPartial(partial)
                            journals.delete(partial)
                            val mismatch = IOException(
                                "${expect.algorithm.jcaName} mismatch for ${t.dest.fileName} from $url: " +
                                    "expected ${expect.value}, got $actual"
                            )
                            lastError = mismatch
                            if (mismatchRetried) break
                            mismatchRetried = true
                            log.warn("transfer: {}; refetching once from the same source", mismatch.message)
                            continue
                        }
                    }
                    commit(partial, t.dest)
                    journals.delete(partial)
                    return fetched
                } catch (e: Throwable) {
                    if (!isTransientTransferError(e)) throw e
                    lastError = e
                    log.warn(
                        "transfer: {} from {} gave up after {} attempts: {}",
                        t.dest.fileName, url, attempts, e.message ?: e::class.simpleName,
                    )
                    break
                }
            }
        }
        throw IOException("every source failed for ${t.dest.fileName}", lastError)
    }

    /**
     * One source, with retries. Chooses between streaming the whole body and
     * fetching blocks, and demotes to streaming when the host turns out not to
     * honour ranges.
     */
    private suspend fun runAttempts(
        t: Transfer,
        url: String,
        partial: Path,
        onProgress: (Long, Long) -> Unit,
    ): Long {
        var lastError: Throwable? = null
        var blocked = t.size >= parallelThreshold ||
            (t.size <= 0L && (journals.read(partial)?.size ?: 0L) >= parallelThreshold)
        repeat(attempts) { attempt ->
            try {
                return if (blocked) fetchBlocks(t, url, partial, onProgress) else streamWhole(t, url, partial, onProgress)
            } catch (e: RangeIgnoredException) {
                // Nothing to wait for: this host serves whole bodies. Switch modes
                // and spend the attempt on the transfer instead of on a backoff.
                log.debug("transfer: {} ignores ranges, streaming {} whole", url, t.dest.fileName)
                blocked = false
                dropPartial(partial)
                journals.delete(partial)
                lastError = e
            } catch (e: Throwable) {
                if (!isTransientTransferError(e)) throw e
                gate.onTransientError()
                lastError = e
                if (attempt < attempts - 1) {
                    val wait = backoffMs.getOrElse(attempt) { backoffMs.last() }
                    log.debug(
                        "transfer: {} attempt {}/{} failed ({}), retrying in {}ms",
                        t.dest.fileName, attempt + 1, attempts, e.message, wait,
                    )
                    delay(wait.milliseconds)
                }
            }
        }
        throw lastError ?: IOException("no attempt was made for ${t.dest.fileName}")
    }

    /**
     * Streams the whole body into the partial, appending to a prefix when there is
     * one worth trusting.
     *
     * A prefix is resumed only when the caller pinned a digest. Without one,
     * nothing would catch old-bytes-plus-new-tail arriving at exactly the expected
     * length, and that combination passes every cheap check there is.
     *
     * When the response declares a length worth blocking, the prefix is truncated
     * to whole blocks and written down as a journal, so the next attempt resumes
     * by block instead of streaming from zero again. That is how a transfer with
     * no declared size -- a JDK archive, a loader installer -- gains resume the
     * moment it first breaks.
     */
    private suspend fun streamWhole(t: Transfer, url: String, partial: Path, onProgress: (Long, Long) -> Unit): Long {
        journals.delete(partial)
        val resumable = t.expect != null
        val have = if (resumable && Files.isRegularFile(partial)) {
            runCatching { Files.size(partial) }.getOrDefault(0L)
        } else {
            dropPartial(partial)
            0L
        }

        var got = 0L
        // Where this attempt started writing. Zero unless the host honoured the
        // range: when it answers with the whole object instead, the prefix is
        // discarded, and counting it would put the transfer past its own length.
        var base = 0L
        var declared = t.size
        var etag: String? = null
        try {
            gate.withPermit {
                request(t, url, if (have > 0L) "bytes=$have-" else null) { resp ->
                    if (have > 0L && resp.status == HttpStatusCode.RequestedRangeNotSatisfiable) {
                        dropPartial(partial)
                        throw RangeNotSatisfiableException(url)
                    }
                    if (!resp.status.isSuccess()) throw httpFailure(url, resp)
                    val resumed = resp.status == HttpStatusCode.PartialContent
                    val bodyLength = resp.contentLength() ?: -1L
                    declared = when {
                        resumed && bodyLength >= 0L -> have + bodyLength
                        bodyLength >= 0L -> bodyLength
                        else -> t.size
                    }
                    etag = resp.headers[HttpHeaders.ETag]
                    val append = resumed && have > 0L
                    if (!append) dropPartial(partial)
                    base = if (append) have else 0L
                    val total = declared.coerceAtLeast(0L)
                    onProgress(base, total)
                    FileOutputStream(partial.toFile(), append).use { out ->
                        drain(resp) { chunk, length ->
                            out.write(chunk, 0, length)
                            got += length
                            onProgress(base + got, total)
                        }
                    }
                }
            }
        } catch (e: Throwable) {
            // Write down what the broken stream did deliver, so the retry resumes
            // by block rather than fetching the whole object again.
            if (isTransientTransferError(e)) noteStreamedPrefix(t, url, partial, base + got, declared, etag)
            throw e
        }
        val total = base + got
        if (declared > 0L && total < declared) {
            noteStreamedPrefix(t, url, partial, total, declared, etag)
            throw EOFException("body for ${t.dest.fileName} ended at $total of $declared bytes")
        }
        return got
    }

    /**
     * Fetches the blocks the journal does not have.
     *
     * The journal is written as each block lands, not once per group. Recording a
     * whole group at its end means a block that fails takes the finished work of
     * its siblings with it -- the next attempt has no record of them, so it fetches
     * them again, and a route that breaks often enough never converges.
     */
    private suspend fun fetchBlocks(t: Transfer, url: String, partial: Path, onProgress: (Long, Long) -> Unit): Long {
        val journal = openJournal(t, url, partial)
        val done = journal.done.toMutableSet()
        val bookkeeping = Mutex()
        val progress = BlockProgress(done.size.toLong() * journal.blockSize, journal.size, onProgress)
        progress.report()

        var moved = 0L
        for (group in (0 until journal.blockCount).filterNot { it in done }.chunked(blocksInFlight)) {
            val landed = coroutineScope {
                group.map { index ->
                    async(Dispatchers.IO) {
                        gate.withPermit { fetchOneBlock(t, url, partial, journal, index, progress) }
                        bookkeeping.withLock {
                            done += index
                            journals.write(partial, journal.copy(done = done.sorted()))
                        }
                        index
                    }
                }.awaitAll()
            }
            moved += landed.sumOf { journal.rangeOf(it).let { r -> r.last - r.first + 1 } }
        }
        return moved
    }

    private suspend fun fetchOneBlock(
        t: Transfer,
        url: String,
        partial: Path,
        journal: TransferJournal,
        index: Int,
        progress: BlockProgress,
    ) {
        val range = journal.rangeOf(index)
        val expected = range.last - range.first + 1
        request(t, url, "bytes=${range.first}-${range.last}") { resp ->
            if (resp.status == HttpStatusCode.OK) throw RangeIgnoredException(url)
            if (resp.status == HttpStatusCode.RequestedRangeNotSatisfiable) {
                // The object is shorter than the journal says, so the whole plan is
                // stale. Drop it and let the retry re-plan against a fresh response.
                dropPartial(partial)
                journals.delete(partial)
                throw RangeNotSatisfiableException(url)
            }
            if (!resp.status.isSuccess()) throw httpFailure(url, resp)
            FileChannel.open(partial, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { file ->
                var at = range.first
                val got = drain(resp) { chunk, length ->
                    if (at + length > range.last + 1) {
                        throw IOException("block $index of ${t.dest.fileName} overran its range")
                    }
                    val buffer = ByteBuffer.wrap(chunk, 0, length)
                    while (buffer.hasRemaining()) at += file.write(buffer, at)
                    progress.advance(length.toLong())
                }
                if (got != expected) {
                    // The block stays unmarked, so the retry asks for the whole range
                    // again; the bytes already at those offsets are simply rewritten.
                    progress.rewind(got)
                    throw EOFException("block $index of ${t.dest.fileName} ended at $got of $expected bytes")
                }
            }
        }
    }

    /**
     * The journal to work against: the stored one while it still describes the
     * object being fetched, a fresh one otherwise.
     *
     * A journal that does not apply takes the partial with it. Keeping bytes whose
     * provenance we cannot state is how a file ends up committed as a mixture of
     * two versions at exactly the right length.
     */
    private suspend fun openJournal(t: Transfer, url: String, partial: Path): TransferJournal {
        val stored = journals.read(partial)
        if (stored != null && appliesTo(stored, t)) return stored
        if (stored != null) log.info("transfer: the journal for {} is stale, starting over", t.dest.fileName)
        dropPartial(partial)
        journals.delete(partial)
        val probe = probe(t, url)
        return TransferJournal(
            url = url,
            size = probe.size,
            blockSize = blockSize,
            expect = t.expect?.value,
            etag = probe.etag,
        ).also { journals.write(partial, it) }
    }

    private fun appliesTo(journal: TransferJournal, t: Transfer): Boolean {
        if (journal.blockSize != blockSize) return false
        if (journal.expect != t.expect?.value) return false
        // A transfer with no declared size adopts the journal's, which is the whole
        // point of having probed for it on the previous run.
        if (t.size > 0L && journal.size != t.size) return false
        return journal.size > 0L
    }

    /**
     * Length and validator of the object, from a one-byte ranged request.
     *
     * A HEAD would be the obvious call and is the wrong one: hosts that serve
     * files perfectly well answer HEAD with 405, and a CDN may answer it from
     * different metadata than the GET. One byte of range proves in a single
     * request both that ranges work here and what the full length is, and the
     * answer comes from the same code path the blocks will use.
     */
    private suspend fun probe(t: Transfer, url: String): Probe = gate.withPermit {
        request(t, url, "bytes=0-0") { resp ->
            when {
                resp.status == HttpStatusCode.PartialContent -> {
                    val total = resp.headers[HttpHeaders.ContentRange]
                        ?.substringAfter('/', "")
                        ?.trim()
                        ?.toLongOrNull()
                        ?: throw RangeIgnoredException(url)
                    // Drain the one byte so the connection goes back to the pool
                    // clean instead of being closed underneath us.
                    drain(resp) { _, _ -> }
                    Probe(total, resp.headers[HttpHeaders.ETag])
                }
                resp.status.isSuccess() -> throw RangeIgnoredException(url)
                else -> throw httpFailure(url, resp)
            }
        }
    }

    private data class Probe(val size: Long, val etag: String?)

    /**
     * Records a streamed prefix as whole blocks so the next attempt can resume.
     *
     * Only whole blocks are kept and the tail is truncated away: a block is either
     * verifiable in full or it is not there, and truncating is what keeps the
     * journal's claim about the file true.
     */
    private fun noteStreamedPrefix(t: Transfer, url: String, partial: Path, have: Long, declared: Long, etag: String?) {
        if (declared < parallelThreshold || have <= 0L) return
        val whole = (have / blockSize).toInt()
        if (whole <= 0) return
        runCatching {
            FileChannel.open(partial, StandardOpenOption.WRITE).use { it.truncate(whole.toLong() * blockSize) }
            journals.write(
                partial,
                TransferJournal(
                    url = url,
                    size = declared,
                    blockSize = blockSize,
                    expect = t.expect?.value,
                    etag = etag,
                    done = (0 until whole).toList(),
                ),
            )
            log.debug(
                "transfer: kept {} of {} blocks of {} for the next attempt",
                whole, blockCountFor(declared, blockSize), t.dest.fileName,
            )
        }
    }

    private suspend fun <R> request(
        t: Transfer,
        url: String,
        range: String?,
        handle: suspend (HttpResponse) -> R,
    ): R = http.current.prepareGet(url) {
        t.userAgent?.let { header(HttpHeaders.UserAgent, it) }
        t.headers.forEach { (name, value) -> header(name, value) }
        range?.let { header(HttpHeaders.Range, it) }
    }.execute { resp -> handle(resp) }

    /** Reads the body in 64 KiB chunks. Returns how many bytes arrived. */
    private suspend inline fun drain(resp: HttpResponse, sink: (ByteArray, Int) -> Unit): Long {
        val channel = resp.bodyAsChannel()
        val buffer = ByteArray(READ_BUFFER)
        var total = 0L
        while (!channel.isClosedForRead) {
            val n = channel.readAvailable(buffer, 0, buffer.size)
            if (n <= 0) break
            sink(buffer, n)
            total += n
            gate.onBytes(n.toLong())
        }
        return total
    }

    private suspend fun httpFailure(url: String, resp: HttpResponse): HttpStatusException {
        val body = runCatching { resp.bodyAsText() }.getOrDefault("").take(HTTP_BODY_EXCERPT)
        return HttpStatusException(resp.status.value, url, body)
    }

    /** True when what is on disk already is what the transfer would produce. */
    private fun alreadySatisfied(t: Transfer): Boolean {
        if (!Files.isRegularFile(t.dest)) return false
        val sizeMatches = { t.size > 0L && runCatching { Files.size(t.dest) == t.size }.getOrDefault(false) }
        return when (t.skip) {
            SkipIfPresent.Never -> false
            SkipIfPresent.Presence -> true
            SkipIfPresent.BySize -> sizeMatches()
            SkipIfPresent.ByDigest -> {
                val expect = t.expect ?: return sizeMatches()
                // Size first: a totally wrong file fails on one stat rather than a
                // full hash walk.
                if (t.size > 0L && !sizeMatches()) return false
                runCatching { expect.matches(expect.algorithm.of(t.dest)) }.getOrDefault(false)
            }
        }
    }

    private fun partialOf(dest: Path): Path = dest.resolveSibling("${dest.fileName}.part")

    private fun dropPartial(partial: Path) {
        runCatching { Files.deleteIfExists(partial) }
    }

    /**
     * Publishes the finished bytes.
     *
     * Both fallbacks are load-bearing. FAT32, exFAT and some SMB shares cannot
     * rename atomically at all; and the platform is allowed to let an atomic move
     * ignore REPLACE_EXISTING, which some providers express by refusing an existing
     * destination outright. Re-syncing over a file that is already there is the
     * normal case, so without the second fallback the normal case fails.
     */
    private fun commit(partial: Path, dest: Path) {
        try {
            Files.move(partial, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
            log.warn(
                "Filesystem at {} does not support ATOMIC_MOVE; a crash mid-rename can leave a truncated file",
                dest.parent,
            )
            Files.move(partial, dest, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: java.nio.file.FileAlreadyExistsException) {
            Files.move(partial, dest, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        /**
         * 8 MiB. Small enough that a reset costs little on a bad line, large enough
         * that a 300 MB pack is under forty requests and a journal write per block
         * costs nothing.
         */
        const val DEFAULT_BLOCK_SIZE = 8 * 1024 * 1024

        /**
         * Below this a transfer is one request. Splitting a 3 MB mod jar into blocks
         * would add a probe, a journal and more requests to save nothing -- the
         * whole file is smaller than one block.
         */
        const val DEFAULT_PARALLEL_THRESHOLD = 24L * 1024 * 1024

        const val DEFAULT_ATTEMPTS = 3
        val DEFAULT_BACKOFF_MS = listOf(1_000L, 3_000L, 9_000L)

        const val DEFAULT_BLOCKS_IN_FLIGHT = 4

        private const val READ_BUFFER = 64 * 1024
        private const val HTTP_BODY_EXCERPT = 512
    }
}
