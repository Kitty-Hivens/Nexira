package hivens.launcher.di

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A SupervisorJob keeps one failed child from taking down its siblings; it does
 * NOT consume the throwable. Without a handler in the scope's context the failure
 * walks out to `Thread.getDefaultUncaughtExceptionHandler`, which this launcher
 * wires to the crash reporter plus a modal "Nexira quit unexpectedly" dialog on
 * the EDT -- the thread Compose draws on. So a background job throwing would
 * freeze the window behind a report of a crash that did not happen.
 *
 * These pin the property directly rather than through Koin: the scope's context
 * is the whole contract, and building it here keeps the test off the DI graph's
 * boot path.
 */
class AppScopeHandlerTest {

    @Test
    fun `a scope with no handler lets the failure reach the default uncaught handler`() {
        val seen = AtomicReference<Throwable?>(null)
        val latch = CountDownLatch(1)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, t -> seen.set(t); latch.countDown() }
        try {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            scope.launch { throw IllegalStateException("background work failed") }
            assertTrue(latch.await(5, TimeUnit.SECONDS), "the handler should have been reached")
            assertNotNull(seen.get(), "this is the shape the crash dialog hangs off")
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    @Test
    fun `the app scope's handler keeps a failed job away from it`() = runBlocking {
        val seen = AtomicReference<Throwable?>(null)
        val handled = AtomicReference<Throwable?>(null)
        val latch = CountDownLatch(1)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, t -> seen.set(t) }
        try {
            val scope = CoroutineScope(
                SupervisorJob() + Dispatchers.IO +
                    CoroutineExceptionHandler { _, t -> handled.set(t); latch.countDown() }
            )
            val job = scope.launch { throw IllegalStateException("background work failed") }
            job.join()
            assertTrue(latch.await(5, TimeUnit.SECONDS))
            assertNotNull(handled.get(), "the scope's handler owns it")
            assertNull(seen.get(), "nothing may reach the crash-dialog path")
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }
}
