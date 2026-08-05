package hivens.core.io

import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class UnpackBudgetTest {

    private lateinit var dir: Path

    @BeforeTest
    fun setup() {
        dir = Files.createTempDirectory("nexira-unpack-budget-test-")
    }

    @AfterTest
    fun teardown() {
        Files.walk(dir).use { walk ->
            walk.sorted(Comparator.reverseOrder()).forEach { entry -> Files.deleteIfExists(entry) }
        }
    }

    @Test
    fun `content within the limits passes through untouched`() {
        val budget = UnpackBudget(UnpackLimits(maxEntries = 3, maxBytes = 100), "test")
        val dest = dir.resolve("a.txt")

        budget.entry()
        budget.copyTo(ByteArrayInputStream("hello".toByteArray()), dest)

        assertEquals("hello", Files.readString(dest))
    }

    @Test
    fun `the entry count is capped`() {
        val budget = UnpackBudget(UnpackLimits(maxEntries = 2, maxBytes = 1_000), "test")
        budget.entry()
        budget.entry()
        assertFailsWith<IOException> { budget.entry() }
    }

    @Test
    fun `the byte budget spans entries, not just one`() {
        // A bomb does not need one enormous member; a great many ordinary ones
        // reach the same place.
        val budget = UnpackBudget(UnpackLimits(maxEntries = 100, maxBytes = 10), "test")
        budget.copyTo(ByteArrayInputStream(ByteArray(6)), dir.resolve("a"))
        assertFailsWith<IOException> {
            budget.copyTo(ByteArrayInputStream(ByteArray(6)), dir.resolve("b"))
        }
    }

    @Test
    fun `a stream that lies about its size is still stopped`() {
        // The point of counting written bytes rather than declared ones: an
        // archive's stated uncompressed size is metadata its author controls,
        // so a bomb simply understates it. Nothing here is asked.
        val budget = UnpackBudget(UnpackLimits(maxEntries = 10, maxBytes = 1024), "test")
        val dest = dir.resolve("bomb")

        assertFailsWith<IOException> {
            budget.copyTo(ByteArrayInputStream(ByteArray(64 * 1024)), dest)
        }

        // And it stopped near the budget rather than after writing everything.
        assertTrue(Files.size(dest) <= 1024 + 8192, "the write ran on past the limit: ${Files.size(dest)} bytes")
    }
}
