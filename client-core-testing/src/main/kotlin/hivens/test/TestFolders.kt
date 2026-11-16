package hivens.test

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.FileSystemException
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The end of a test that worked in the temporary folder [dir]: [scope] is cancelled and every
 * coroutine in it waited for, [settle] then lets finish what runs outside coroutines (a writer's
 * queue, a class loader open on a jar), and only after that is [dir] deleted.
 *
 * Cancelling a scope does not wait for it. A coroutine still inside a write holds its file open,
 * and Linux deletes a folder around an open file where Windows refuses, so a teardown that only
 * cancels passes on one and fails on the other.
 *
 * [delete] is how the folder is deleted, there so the test of this helper can stand in a refusal
 * the way Windows gives one.
 */
fun stopThenDelete(
    scope: CoroutineScope,
    dir: Path,
    settle: () -> Unit = {},
    delete: (Path) -> Unit = ::deleteTree,
) {
    scope.stopAndAwait()
    settle()
    deleteOnceReleased(dir, delete = delete)
}

/**
 * Cancels this scope and waits up to [timeout] for its coroutines to finish. One that has not
 * finished by then fails the test, naming what is still running, rather than leave the teardown
 * waiting for good.
 */
fun CoroutineScope.stopAndAwait(timeout: Duration = STOP_TIMEOUT) {
    val job = coroutineContext.job
    val stopped = runBlocking { withTimeoutOrNull(timeout) { job.cancelAndJoin() } }
    if (stopped == null) {
        throw AssertionError("the test's coroutines were still running $timeout after it ended: ${unfinished(job)}")
    }
}

/**
 * Deletes [dir] and everything under it. A refusal, which Windows gives for a file something
 * still has open, is tried again, [tries] times in all and [pause] apart, and the last one is
 * thrown.
 */
fun deleteOnceReleased(
    dir: Path,
    tries: Int = DELETE_TRIES,
    pause: Duration = DELETE_PAUSE,
    delete: (Path) -> Unit = ::deleteTree,
) {
    require(tries >= 1) { "at least one try, not $tries" }
    repeat(tries - 1) {
        try {
            delete(dir)
            return
        } catch (_: FileSystemException) {
            Thread.sleep(pause.inWholeMilliseconds)
        }
    }
    delete(dir)
}

/** Long enough for a write on a slow CI disk, short enough that a test stuck in teardown says so. */
val STOP_TIMEOUT: Duration = 10.seconds

/** Ten tries fifty milliseconds apart: half a second for a handle to go once the work holding it ended. */
const val DELETE_TRIES: Int = 10

val DELETE_PAUSE: Duration = 50.milliseconds

@OptIn(ExperimentalPathApi::class)
private fun deleteTree(dir: Path) = dir.deleteRecursively()

private fun unfinished(job: Job): List<Job> =
    job.children.filterNot { it.isCompleted }.flatMap { child -> listOf(child) + unfinished(child) }.toList()
