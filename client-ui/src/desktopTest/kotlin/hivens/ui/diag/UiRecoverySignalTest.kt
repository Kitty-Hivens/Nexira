package hivens.ui.diag

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Covers the render-path crash side-channel, the recovered flag, and the rules
 * that decide when a restart becomes safe mode.
 *
 * UiRecoverySignal is a process-global object and `recordShellCrash` latches
 * safeMode irreversibly, so every test resets the whole object first. Without
 * that, one test that trips the guard makes every later call in the same fork
 * return FATAL and the suite becomes order-dependent -- a single regression
 * would surface as a wall of unrelated failures.
 */
class UiRecoverySignalTest {

    @BeforeTest
    fun resetLatch() = UiRecoverySignal.resetForTests()

    @BeforeTest
    @AfterTest
    fun drain() {
        UiRecoverySignal.consumePendingCrash()
        UiRecoverySignal.consumeRecovered()
    }

    @Test
    fun `pending crash starts empty`() {
        assertNull(UiRecoverySignal.consumePendingCrash())
    }

    @Test
    fun `record then consume returns and clears the crash`() {
        val boom = IllegalStateException("boom")
        UiRecoverySignal.recordPendingCrash(boom)

        assertSame(boom, UiRecoverySignal.consumePendingCrash())
        assertNull(UiRecoverySignal.consumePendingCrash())
    }

    @Test
    fun `first recorded crash wins until consumed`() {
        val first  = RuntimeException("first")
        val second = RuntimeException("second")
        UiRecoverySignal.recordPendingCrash(first)
        UiRecoverySignal.recordPendingCrash(second)

        assertSame(first, UiRecoverySignal.consumePendingCrash())
    }

    @Test
    fun `recovered flag is one-shot`() {
        assertFalse(UiRecoverySignal.consumeRecovered())

        UiRecoverySignal.markRecovered()
        assertTrue(UiRecoverySignal.consumeRecovered())
        assertFalse(UiRecoverySignal.consumeRecovered())
    }

    @Test
    fun `idempotent marking still consumes once`() {
        UiRecoverySignal.markRecovered()
        UiRecoverySignal.markRecovered()

        assertEquals(true, UiRecoverySignal.consumeRecovered())
        assertFalse(UiRecoverySignal.consumeRecovered())
    }

    // --- Structural (class-linkage) crash detection: the deterministic-fault
    //     branch that latches safe mode one restart sooner. Pure, so it is safe
    //     to exercise without latching safeMode.

    @Test
    fun `a NoClassDefFoundError is structural`() {
        assertTrue(UiRecoverySignal.isStructural(NoClassDefFoundError("hivens/ui/diag/CrashDialog")))
    }

    @Test
    fun `a ClassNotFoundException wrapped in an ordinary exception is structural`() {
        val boom = RuntimeException("shell boot", ClassNotFoundException("hivens.ui.diag.CrashDialog"))
        assertTrue(UiRecoverySignal.isStructural(boom))
    }

    @Test
    fun `an ordinary logic exception is not structural`() {
        assertFalse(UiRecoverySignal.isStructural(IllegalStateException("a real bug, retry may help")))
    }

    @Test
    fun `a cyclic cause chain terminates without a linkage error`() {
        val a = RuntimeException("a")
        val b = RuntimeException("b")
        a.initCause(b)
        b.initCause(a)
        assertFalse(UiRecoverySignal.isStructural(a))
    }

    @Test
    fun `a healthy session stops the crashes before it counting as a loop`() {
        // The reported failure: crash, come back, be used, crash, come back, be
        // used, crash -- and the third lands in the recovery surface even though
        // the launcher worked every time. Three short sessions fit inside one
        // twenty-second window.
        val t0 = 1_000_000L
        assertEquals(ShellRecovery.RETRY, UiRecoverySignal.recordShellCrash(t0))
        assertEquals(ShellRecovery.RETRY, UiRecoverySignal.recordShellCrash(t0 + 5_000))

        UiRecoverySignal.noteShellHealthy(t0 + 6_000)

        assertEquals(ShellRecovery.RETRY, UiRecoverySignal.recordShellCrash(t0 + 10_000))
    }

    @Test
    fun `a healthy session does not disarm the long window`() {
        // The regression that has to stay fixed: an earlier version cleared the
        // crash history outright, so a fault that let the shell come up before
        // killing it again reset the count every time and safe mode could never
        // latch -- an unbounded restart spin instead of a guard. Every crash is
        // still counted for the long window; only the tight-loop window ignores
        // what happened before a session that lasted.
        val t0 = 2_000_000L
        repeat(6) { i ->
            UiRecoverySignal.noteShellHealthy(t0 + i * 30_000L - 1)
            assertEquals(ShellRecovery.RETRY, UiRecoverySignal.recordShellCrash(t0 + i * 30_000L))
        }
        UiRecoverySignal.noteShellHealthy(t0 + 6 * 30_000L - 1)
        assertEquals(ShellRecovery.SAFE_MODE, UiRecoverySignal.recordShellCrash(t0 + 6 * 30_000L))
    }
}
