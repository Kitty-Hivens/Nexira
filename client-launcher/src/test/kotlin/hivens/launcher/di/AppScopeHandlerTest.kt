package hivens.launcher.di

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A SupervisorJob keeps one failed child from taking down its siblings; it does
 * NOT consume the throwable. Without a handler in the scope's context the failure
 * walks out to `Thread.getDefaultUncaughtExceptionHandler`, which this launcher
 * wires to the crash reporter plus a modal "Nexira quit unexpectedly" dialog on
 * the EDT -- the thread Compose draws on. So a background job throwing would
 * freeze the window behind a report of a crash that did not happen.
 *
 * The negative case is deliberately NOT exercised: letting an exception go
 * genuinely uncaught records it in the coroutines test module's global capture and
 * fails the next `runTest` anywhere in the same JVM. Asserting the handler is
 * present, and that it receives the failure, pins the same contract without
 * leaving a live grenade in a shared test process.
 */
class AppScopeHandlerTest {

    private lateinit var dataDir: Path

    @BeforeTest
    fun setup() {
        dataDir = Files.createTempDirectory("nexira-app-scope-test-")
        startKoin { modules(module { single { dataDir } }, networkModule, appModule) }
    }

    @AfterTest
    fun teardown() {
        stopKoin()
        Files.walk(dataDir).use { walk ->
            walk.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }

    @Test
    fun `the shared app scope carries an exception handler`() {
        val scope: CoroutineScope = org.koin.java.KoinJavaComponent.get(CoroutineScope::class.java)
        assertNotNull(
            scope.coroutineContext[CoroutineExceptionHandler],
            "without one, a failed background job opens the crash dialog on a healthy process",
        )
    }

    @Test
    fun `a handler in the context receives the failure instead of the thread's`() = runBlocking {
        val handled = AtomicReference<Throwable?>(null)
        val latch = CountDownLatch(1)
        val scope = CoroutineScope(
            SupervisorJob() + Dispatchers.IO +
                CoroutineExceptionHandler { _, t -> handled.set(t); latch.countDown() }
        )

        scope.launch { throw IllegalStateException("background work failed") }.join()

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertNotNull(handled.get(), "the scope's handler owns it, so nothing reaches the crash path")
    }
}
