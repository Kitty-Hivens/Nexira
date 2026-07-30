package hivens.core.net

import io.ktor.utils.io.ClosedByteChannelException
import kotlinx.coroutines.CancellationException
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.nio.channels.ClosedChannelException

/**
 * Whether [t] is the kind of failure that another attempt can plausibly get
 * past.
 *
 * The decision is made on exception TYPE wherever a type exists, walking the
 * cause chain, because the shapes we care about arrive wrapped: a peer resetting
 * the stream mid-body surfaces as ktor closing the channel with that cause, and
 * the interesting class sits two levels down. Two exceptions to the
 * type-only rule are named below.
 *
 * [CancellationException] is checked first and is never transient. It is how a
 * parent coroutine says stop -- a user pressing Cancel, or the launcher shutting
 * down -- and retrying it would turn cancellation into a loop that finishes the
 * work anyway.
 */
fun isTransientTransferError(t: Throwable): Boolean {
    if (t is CancellationException) return false
    var cause: Throwable? = t
    var depth = 0
    while (cause != null && depth < MAX_CAUSE_DEPTH) {
        if (cause is CancellationException) return false
        // A status the host will answer the same way forever ends the attempt here;
        // a busy or briefly broken host is worth asking again.
        if (cause is HttpStatusException) return cause.retryable
        when (cause) {
            // Local recovery already happened at the throw site; the retry is
            // what actually restarts the transfer.
            is RangeNotSatisfiableException,
            is RangeIgnoredException,
            is ConnectException,
            is SocketException,
            is SocketTimeoutException,
            is ClosedByteChannelException,
            is ClosedChannelException,
            // okhttp raises this on a body that ends before its declared length,
            // which is exactly a cut transfer.
            is EOFException,
            -> return true
            else -> Unit
        }
        // Message-based checks, deliberately. The types are not reachable from
        // here: okhttp's StreamResetException lives in an internal package, and a
        // reset that arrives as a plain IOException carries its reason only in
        // text. The Windows sharing-violation text is localized and must never be
        // matched this way, but these are protocol strings from a library we ship,
        // not from the OS.
        if (cause is IOException) {
            if (cause.javaClass.simpleName == "StreamResetException") return true
            val message = cause.message
            if (message != null && TRANSIENT_MESSAGES.any { message.contains(it, ignoreCase = true) }) {
                return true
            }
        }
        cause = cause.cause
        depth++
    }
    return false
}

/**
 * A cause chain long enough to hit this is a wrapping bug, not a network
 * failure; the bound keeps a self-referential chain from spinning here.
 */
private const val MAX_CAUSE_DEPTH = 12

/**
 * `stream was reset` is okhttp's own wording for the h2 case this whole retry
 * path was written for -- a middlebox sending RST_STREAM mid-body. It is matched
 * by text as well as by type because the type does not always survive the trip:
 * ktor wraps, and the reason can reach us as a plain IOException.
 */
private val TRANSIENT_MESSAGES = listOf(
    "connection reset",
    "unexpected end of stream",
    "stream was reset",
)
