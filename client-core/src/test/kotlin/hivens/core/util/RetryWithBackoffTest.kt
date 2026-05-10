package hivens.core.util

import kotlinx.coroutines.test.runTest
import java.io.IOException
import java.net.SocketException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RetryWithBackoffTest {

    @Test
    fun `succeeds on first attempt without delay`() = runTest {
        var calls = 0
        val result = retryWithBackoff(
            operation = "test",
            shouldRetry = { true },
        ) {
            calls++
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(1, calls)
    }

    @Test
    fun `retries on retryable exception then succeeds`() = runTest {
        var calls = 0
        val result = retryWithBackoff(
            operation = "test",
            shouldRetry = { it is SocketException },
        ) {
            calls++
            if (calls < 2) throw SocketException("Connection reset")
            "recovered"
        }
        assertEquals("recovered", result)
        assertEquals(2, calls)
    }

    @Test
    fun `bubbles non-retryable exception immediately`() = runTest {
        var calls = 0
        assertFailsWith<IllegalStateException> {
            retryWithBackoff(
                operation = "test",
                shouldRetry = { it is SocketException },
            ) {
                calls++
                throw IllegalStateException("don't retry me")
            }
        }
        assertEquals(1, calls, "non-retryable exception must not trigger a retry")
    }

    @Test
    fun `gives up after max attempts and throws last exception`() = runTest {
        var calls = 0
        val ex = assertFailsWith<IOException> {
            retryWithBackoff(
                operation = "test",
                attempts = 3,
                shouldRetry = { true },
            ) {
                calls++
                throw IOException("attempt $calls")
            }
        }
        assertEquals(3, calls)
        assertTrue(ex.message!!.contains("attempt 3"), "last attempt's exception should bubble up")
    }

    @Test
    fun `attempts of 1 means no retry`() = runTest {
        var calls = 0
        assertFailsWith<IOException> {
            retryWithBackoff(
                operation = "test",
                attempts = 1,
                shouldRetry = { true },
            ) {
                calls++
                throw IOException("once")
            }
        }
        assertEquals(1, calls)
    }
}
