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
        // Two message-based checks, deliberately. Neither type is reachable from
        // here: okhttp's StreamResetException lives in an internal package, and a
        // reset arriving as a plain IOException carries the reason only in text.
        // The Windows sharing-violation text is localized and must never be
        // matched this way, but these two are protocol strings from a library we
        // ship, not from the OS.
        if (cause is IOException) {
            val message = cause.message
            if (cause.javaClass.simpleName == "StreamResetException") return true
            if (message != null && (
                    message.contains("Connection reset", ignoreCase = true) ||
                        message.contains("unexpected end of stream", ignoreCase = true)
                    )
            ) {
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
