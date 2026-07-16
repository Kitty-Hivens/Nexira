package hivens.core.io

import java.nio.file.AccessDeniedException
import java.nio.file.FileSystemException
import java.nio.file.NoSuchFileException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FileOpRetryTest {

    @Test
    fun `retries a transient FileSystemException then succeeds`() {
        var calls = 0
        val result = fileOpRetry("t", attempts = 5, maxBackoffMs = 1) {
            calls++
            if (calls < 3) throw FileSystemException("f", null, "used by another process")
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(3, calls)
    }

    @Test
    fun `treats AccessDeniedException as transient`() {
        var calls = 0
        val result = fileOpRetry("t", attempts = 3, maxBackoffMs = 1) {
            calls++
            if (calls < 2) throw AccessDeniedException("f")
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(2, calls)
    }

    @Test
    fun `gives up after the attempt budget and rethrows`() {
        var calls = 0
        assertFailsWith<FileSystemException> {
            fileOpRetry("t", attempts = 4, maxBackoffMs = 1) {
                calls++
                throw FileSystemException("still locked")
            }
        }
        assertEquals(4, calls)
    }

    @Test
    fun `does not retry a permanent shape like NoSuchFileException`() {
        var calls = 0
        assertFailsWith<NoSuchFileException> {
            fileOpRetry("t", attempts = 5, maxBackoffMs = 1) {
                calls++
                throw NoSuchFileException("gone")
            }
        }
        assertEquals(1, calls)
    }

    @Test
    fun `passes a non-filesystem error straight through`() {
        var calls = 0
        assertFailsWith<IllegalStateException> {
            fileOpRetry("t", attempts = 5, maxBackoffMs = 1) {
                calls++
                throw IllegalStateException("boom")
            }
        }
        assertEquals(1, calls)
    }
}
