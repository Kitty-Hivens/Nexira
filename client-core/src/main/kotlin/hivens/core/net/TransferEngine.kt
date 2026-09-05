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
import kotlinx.coroutines.CancellationException
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
    private val blockMaps: BlockMapStore = BlockMapStore(),
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
     * Checks every transfer against what it is supposed to be and puts right what
     * is not, fetching as little as the evidence allows.
     *
     * A file with a block map is compared block by block and only the blocks that
     * differ are pulled, so a corrupted sector in a large pack asset costs
     * megabytes instead of the whole file. Without a map -- a small file, or one
     * installed before its map existed -- the whole-file digest is the only
     * available verdict and a full refetch is the only available repair.
     *
     * Repair writes the corrected blocks into the file in place. That is safe
     * precisely because the file is already known to be wrong: a crash partway
     * leaves it no less usable than it was, and the whole-file digest is checked
     * again afterwards. If it still does not match, the file is fetched from
     * scratch rather than left in a state nobody can vouch for.
     */
    suspend fun verifyAndRepair(
        transfers: List<Transfer>,
        onProgress: (TransferProgress) -> Unit = {},
    ): RepairReport = withContext(Dispatchers.IO) {
        val repaired = ArrayList<String>()
        val failed = LinkedHashMap<String, String>()
        var intact = 0
        var moved = 0L
        val tracker = SetProgress(transfers.sumOf { it.size.coerceAtLeast(0L) }, transfers.size, onProgress)

        for (t in transfers) {
            tracker.starting(t)
            val label = t.dest.fileName.toString()
            try {
                val outcome = verifyOne(t)
                moved += outcome.bytesFetched
                if (outcome.wasBroken) repaired += label else intact++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                failed[label] = e.message ?: e::class.simpleName.orEmpty()
                log.warn("repair: {} could not be put right", label, e)
            }
            tracker.finished(t)
        }
        RepairReport(
            checked = transfers.size,
            intact = intact,
            repaired = repaired,
            bytesFetched = moved,
            failed = failed,
        )
    }

    private data class VerifyOutcome(val wasBroken: Boolean, val bytesFetched: Long)

    private suspend fun verifyOne(t: Transfer): VerifyOutcome = withDestLock(t.dest) {
        val expect = t.expect
        if (!Files.isRegularFile(t.dest)) {
            return@withDestLock VerifyOutcome(true, fetchLocked(t))
        }
        if (expect == null) {
            // Nothing to check against, so the only claim that can be made is that
            // something is there -- which it is.
            return@withDestLock VerifyOutcome(false, 0L)
        }
        val map = blockMaps.read(t.dest)?.takeIf { it.applies(expect, t.size, blockSize) }
        if (map == null) {
            val actual = expect.algorithm.of(t.dest)
            if (expect.matches(actual)) return@withDestLock VerifyOutcome(false, 0L)
            log.info("repair: {} does not match its digest and has no block map; refetching in full", t.dest.fileName)
            return@withDestLock VerifyOutcome(true, fetchLocked(t))
        }

        val onDisk = expect.algorithm.ofWithBlocks(t.dest, map.blockSize)
        if (expect.matches(onDisk.whole)) return@withDestLock VerifyOutcome(false, 0L)
        val bad = map.blocks.indices.filter { onDisk.blocks.getOrNull(it) != map.blocks[it] }
        if (bad.isEmpty()) {
            // The blocks all agree and the whole file does not, which means the map
            // and the file disagree about the length. Nothing here is trustworthy.
            log.info("repair: {} has a block map that does not describe it; refetching in full", t.dest.fileName)
            return@withDestLock VerifyOutcome(true, fetchLocked(t))
        }
        log.info("repair: {} is missing {} of {} blocks", t.dest.fileName, bad.size, map.blocks.size)
        val patched = runCatching { patchBlocks(t, map, bad) }
        val fixed = patched.getOrNull()
        if (fixed != null && expect.matches(expect.algorithm.of(t.dest))) {
            return@withDestLock VerifyOutcome(true, fixed)
        }
        log.info("repair: block repair of {} did not settle it; refetching in full", t.dest.fileName)
        VerifyOutcome(true, (fixed ?: 0L) + fetchLocked(t))
    }

    /** Pulls [bad] blocks straight into the destination. Returns the bytes fetched. */
    private suspend fun patchBlocks(t: Transfer, map: BlockMap, bad: List<Int>): Long {
        val url = t.sources.first()
        var moved = 0L
        for (group in bad.chunked(blocksInFlight)) {
            moved += coroutineScope {
                group.map { index ->
                    async(Dispatchers.IO) {
                        val range = map.rangeOf(index)
                        withBlockRetry(t, index) {
                            gate.withPermit { fetchRange(t, url, t.dest, range, null) }
                        }
                        range.last - range.first + 1
                    }
                }.awaitAll().sum()
            }
        }
        return moved
    }

    /**
     * A block, with the transfer's retry budget spent on it alone. Repair has no
     * journal to fall back on -- it is patching a live file -- so a block that will
     * not arrive has to give up and let the caller refetch the whole thing.
     */
    private suspend fun withBlockRetry(t: Transfer, index: Int, block: suspend () -> Unit) {
        var lastError: Throwable? = null
        repeat(attempts) { attempt ->
            try {
                return block()
            } catch (e: RangeNotSatisfiableException) {
                // The object is shorter than the map claims. No number of attempts
                // changes that, and the caller's refetch is the answer.
                throw e
            } catch (e: Throwable) {
                if (!isTransientTransferError(e)) throw e
                gate.onTransientError()
                lastError = e
                if (attempt < attempts - 1) delay(backoffMs.getOrElse(attempt) { backoffMs.last() }.milliseconds)
            }
        }
        throw lastError ?: IOException("block $index of ${t.dest.fileName} was never attempted")
    }

    /** [fetch] without taking the destination lock, for callers that already hold it. */
    private suspend fun fetchLocked(t: Transfer): Long {
        t.dest.parent?.let { Files.createDirectories(it) }
        blockMaps.delete(t.dest)
        return fetchFromAnySource(t) { _, _ -> }
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
                    var snapped: FileDigests? = null
                    if (expect != null) {
                        // One read answers both questions for a large file: whether the
                        // bytes are right, and -- since they are -- what each block of
                        // them hashes to. Taken separately that would be a second full
                        // read of a 300 MB file for a map we could have had for free.
                        snapped = if (worthBlocking(partial)) {
                            expect.algorithm.ofWithBlocks(partial, blockSize)
                        } else {
                            FileDigests(expect.algorithm.of(partial), emptyList(), blockSize)
                        }
                        val actual = snapped.whole
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
                    recordBlockMap(t, snapped)
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
        try {
            fetchRange(t, url, partial, journal.rangeOf(index), progress)
        } catch (e: RangeNotSatisfiableException) {
            // The object is shorter than the journal says, so the whole plan is
            // stale. Drop it and let the retry re-plan against a fresh response.
            dropPartial(partial)
            journals.delete(partial)
            throw e
        }
    }

    /**
     * Fetches one byte range into [target] at its own offsets.
     *
     * Positional writes, so callers may run several ranges of one file at once, and
     * so a range can be written into a file that already exists -- which is what
     * repair does. A range that arrives short is not written off as done: the
     * partial bytes sit at the right offsets and are simply overwritten when the
     * range is asked for again.
     */
    private suspend fun fetchRange(
        t: Transfer,
        url: String,
        target: Path,
        range: LongRange,
        progress: BlockProgress?,
    ) {
        val expected = range.last - range.first + 1
        request(t, url, "bytes=${range.first}-${range.last}") { resp ->
            if (resp.status == HttpStatusCode.OK) throw RangeIgnoredException(url)
            if (resp.status == HttpStatusCode.RequestedRangeNotSatisfiable) throw RangeNotSatisfiableException(url)
            if (!resp.status.isSuccess()) throw httpFailure(url, resp)
            FileChannel.open(target, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { file ->
                var at = range.first
                val got = drain(resp) { chunk, length ->
                    if (at + length > range.last + 1) {
                        throw IOException("range ${range.first}-${range.last} of ${t.dest.fileName} overran itself")
                    }
                    val buffer = ByteBuffer.wrap(chunk, 0, length)
                    while (buffer.hasRemaining()) at += file.write(buffer, at)
                    progress?.advance(length.toLong())
                }
                if (got != expected) {
                    progress?.rewind(got)
                    throw EOFException(
                        "range ${range.first}-${range.last} of ${t.dest.fileName} ended at $got of $expected bytes"
                    )
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
     *
     * With a digest pinned, [appliesTo] is the whole test: a mixture fails
     * verification at the end and the transfer starts over, so the journal is
     * trusted without asking the host anything. With no digest, nothing downstream
     * would notice -- same name, same length, different bytes, which is exactly
     * what a rolling release URL serves after a release -- so the validator
     * recorded when the journal was written is checked against the host, and that
     * costs the one probe request this path otherwise saves.
     */
    private suspend fun openJournal(t: Transfer, url: String, partial: Path): TransferJournal {
        val stored = journals.read(partial)
        val applies = stored != null && appliesTo(stored, t)
        if (applies && t.expect != null) return stored

        val probe = probe(t, url)
        if (applies && stored.size == probe.size && validatorHolds(stored.etag, probe.etag)) return stored

        if (stored != null) {
            log.info(
                "transfer: the journal for {} no longer describes what {} serves, starting over",
                t.dest.fileName, url,
            )
        }
        dropPartial(partial)
        journals.delete(partial)
        return TransferJournal(
            url = url,
            size = probe.size,
            blockSize = blockSize,
            expect = t.expect?.value,
            etag = probe.etag,
        ).also { journals.write(partial, it) }
    }

    /**
     * Whether the host's validator still matches the one recorded beside the
     * partial. A host that sent none either time leaves nothing to compare and the
     * length is all there is -- accepting that is the same bet the journal made
     * when it was written, not a new one.
     */
    private fun validatorHolds(recorded: String?, current: String?): Boolean =
        recorded == null || current == null || recorded == current

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

    /**
     * Whether a file is large enough for a block map to be worth keeping. Below the
     * threshold a whole-file read is cheaper than the bookkeeping, and refetching a
     * small file is not a repair worth optimising.
     */
    private fun worthBlocking(file: Path): Boolean =
        runCatching { Files.size(file) >= parallelThreshold }.getOrDefault(false)

    /**
     * Stores the block hashes taken while the bytes were being verified, so a later
     * repair can find the damaged part of the file instead of replacing all of it.
     */
    private fun recordBlockMap(t: Transfer, snapped: FileDigests?) {
        val expect = t.expect ?: return
        if (snapped == null || snapped.blocks.isEmpty()) return
        blockMaps.write(
            t.dest,
            BlockMap(
                algorithm = expect.algorithm,
                digest = snapped.whole,
                size = runCatching { Files.size(t.dest) }.getOrDefault(-1L),
                blockSize = snapped.blockSize,
                blocks = snapped.blocks,
            ),
        )
    }

    private fun partialOf(dest: Path): Path = TransferStaging.partialOf(dest)

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
