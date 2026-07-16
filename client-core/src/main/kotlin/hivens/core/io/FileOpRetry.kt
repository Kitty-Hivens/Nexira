package hivens.core.io

import org.slf4j.LoggerFactory
import java.nio.file.DirectoryNotEmptyException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.NoSuchFileException

private val log = LoggerFactory.getLogger("FileOpRetry")

/**
 * Runs a file mutation (move/delete) that can transiently fail on Windows with a
 * sharing violation while some other handle is briefly open: antivirus scanning a
 * just-written jar, the running game's classloader, or a reader that did not open
 * with delete-sharing. Retries [attempts] times with short exponential backoff
 * (starts at 50ms, capped at [maxBackoffMs]) on a transient [FileSystemException];
 * the known-permanent shapes (missing source, target exists, non-empty directory)
 * and every non-filesystem error bubble immediately.
 *
 * Blocking (Thread.sleep) so non-suspend callers such as `relabel` can use it;
 * always call off the UI thread (Dispatchers.IO). [operation] appears in the
 * give-up log so a flapping call is identifiable.
 *
 * Locale note: the Windows "used by another process" text is localized, so the
 * transient decision is made on exception TYPE, never on the message string.
 */
fun <T> fileOpRetry(
    operation: String,
    attempts: Int = 5,
    maxBackoffMs: Long = 500,
    block: () -> T,
): T {
    require(attempts >= 1) { "attempts must be >= 1, got $attempts" }
    var backoff = 50L
    repeat(attempts) { i ->
        try {
            return block()
        } catch (e: FileSystemException) {
            if (!isTransient(e) || i == attempts - 1) {
                if (isTransient(e)) {
                    log.warn("{} still failing after {} attempts: {}", operation, attempts, e.message)
                }
                throw e
            }
            Thread.sleep(backoff)
            backoff = (backoff * 2).coerceAtMost(maxBackoffMs)
        }
    }
    error("unreachable: the final attempt returns or throws")
}

// A sharing/lock violation surfaces as a bare FileSystemException (or an
// AccessDeniedException, its subclass) -- both are treated transient. The three
// excluded subclasses describe a state no retry can fix.
private fun isTransient(e: FileSystemException): Boolean = when (e) {
    is NoSuchFileException,
    is FileAlreadyExistsException,
    is DirectoryNotEmptyException -> false
    else -> true
}
