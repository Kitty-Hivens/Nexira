package hivens.ui.utils

import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

/**
 * Writes the interface's own choices to disk off the event thread, one at a time and
 * in the order they were made.
 *
 * Each of these writes replaces a file atomically and flushes it and its directory
 * to the disk. Made on the event thread, a theme flip, a language switch or a ticked
 * box held the window still for as long as the disk took, which on a slow or sleeping
 * drive reads as a hang. Off it, the order still has to hold: two flips of one switch
 * landing the other way round leave the first one on disk. So there is one writer.
 *
 * [drain] lets what is still queued land before the process goes, so a choice made
 * just before quitting is not lost to the move off the event thread.
 */
class PreferenceWriter(threadName: String = "nexira-preferences") {
    private val log = LoggerFactory.getLogger(PreferenceWriter::class.java)
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread(task, threadName).apply { isDaemon = true }
    }

    /** Queues [block], which saves [what]. A failure is logged, and the writes queued after it still run. */
    fun write(what: String, block: () -> Unit) {
        val task = Runnable { runCatching(block).onFailure { log.warn("Could not save {}", what, it) } }
        // Refused only once draining has begun, and the caller is then on the way out
        // too, so the write is made where it was asked for rather than dropped.
        try {
            executor.execute(task)
        } catch (_: RejectedExecutionException) {
            task.run()
        }
    }

    /** Waits up to [timeout] for every write already queued. A write asked for after this runs on its caller. */
    fun drain(timeout: Duration) {
        executor.shutdown()
        if (!executor.awaitTermination(timeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)) {
            log.warn("Preference writes still queued after {}, leaving them", timeout)
        }
    }
}
