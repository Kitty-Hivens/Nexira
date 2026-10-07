package hivens.ui.system

import dev.hivens.libnotify.Notification
import dev.hivens.libnotify.NotificationEvent
import dev.hivens.libnotify.NotificationHandle
import dev.hivens.libnotify.Notifier
import dev.hivens.libnotify.NotifierCapabilities
import dev.hivens.libnotify.NotifierConfig
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A shell restart after a crash shuts the old composition's notifier down while
 * the new one starts, and reaching the daemon takes long enough for the two to
 * meet. Driven through a factory that holds the creation open, since a test has
 * no daemon to reach.
 */
class SystemNotifierLifecycleTest {

    private class FakeNotifier : Notifier {
        @Volatile var closed = false
        override val isOpen: Boolean get() = !closed
        override val capabilities = NotifierCapabilities(false, 0, false, false, false, false, false)
        override fun notify(notification: Notification): NotificationHandle? = null
        override fun cancel(handle: NotificationHandle): Boolean = false
        override fun onEvent(listener: (NotificationEvent) -> Unit): () -> Unit = {}
        override fun close() { closed = true }
    }

    private val realFactory = SystemNotifier.factory

    @AfterTest
    fun restore() {
        SystemNotifier.shutdown()
        SystemNotifier.factory = realFactory
    }

    // The config refuses an empty icon, so the init is handed one byte of one.
    private fun init() = SystemNotifier.init("Nexira", "dev.hivens.nexira", byteArrayOf(1))

    /** Starts an init whose creation waits on [release], and returns once it is waiting. */
    private fun slowInit(made: FakeNotifier, release: CountDownLatch): Thread {
        val entered = CountDownLatch(1)
        SystemNotifier.factory = { entered.countDown(); release.await(5, TimeUnit.SECONDS); made }
        val t = thread { init() }
        assertTrue(entered.await(5, TimeUnit.SECONDS), "the init never reached the daemon")
        return t
    }

    @Test
    fun `a notifier made after a shutdown is closed rather than left open`() {
        val made = FakeNotifier()
        val release = CountDownLatch(1)
        val init = slowInit(made, release)

        SystemNotifier.shutdown()
        release.countDown()
        init.join(5_000)

        assertTrue(made.closed, "the connection outlived the shutdown with nothing left to close it")
        assertFalse(SystemNotifier.isSupported)
    }

    @Test
    fun `the new composition keeps its notifier and the old one closes its own`() {
        val old = FakeNotifier()
        val release = CountDownLatch(1)
        val oldInit = slowInit(old, release)

        SystemNotifier.shutdown()
        val fresh = FakeNotifier()
        SystemNotifier.factory = { fresh }
        init()
        release.countDown()
        oldInit.join(5_000)

        assertTrue(old.closed, "the old connection was left open")
        assertFalse(fresh.closed, "the new one was closed in its place")
        assertTrue(SystemNotifier.isSupported)
    }

    @Test
    fun `a second init while one is reaching the daemon does not start another`() {
        val first = FakeNotifier()
        val release = CountDownLatch(1)
        val init = slowInit(first, release)
        var secondMade = false

        SystemNotifier.factory = { secondMade = true; FakeNotifier() }
        init()
        release.countDown()
        init.join(5_000)

        assertFalse(secondMade, "a second connection was opened over the first")
        assertFalse(first.closed)
        assertTrue(SystemNotifier.isSupported)
    }
}
