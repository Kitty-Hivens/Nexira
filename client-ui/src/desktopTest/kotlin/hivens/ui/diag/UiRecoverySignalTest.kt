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
 * Covers the render-path crash side-channel and the recovered flag.
 * UiRecoverySignal is a process-global object, so each test clears the two
 * one-shot fields it touches first -- it deliberately never calls
 * recordShellCrash(), which latches safeMode irreversibly for the JVM.
 */
class UiRecoverySignalTest {

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
}
