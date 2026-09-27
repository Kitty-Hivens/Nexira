package hivens.ui.utils

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LogRetentionTest {

    @TempDir lateinit var dir: Path

    private val now = Instant.parse("2026-09-28T12:00:00Z")

    private fun file(name: String, ageDays: Long, bytes: Int = 10, ageMinutes: Long = 0): Path {
        val p = dir.resolve(name)
        Files.write(p, ByteArray(bytes))
        Files.setLastModifiedTime(p, FileTime.from(now.minus(Duration.ofDays(ageDays)).minus(Duration.ofMinutes(ageMinutes))))
        return p
    }

    private fun sweep(maxBytes: Long = LogRetention.MAX_TOTAL_BYTES) =
        LogRetention(dir, maxAge = Duration.ofDays(30), maxTotalBytes = maxBytes, now = { now }).sweep()

    @Test
    fun `session logs past the age limit go, recent ones stay`() {
        val old = file("game-output-pack-2026-07-01.log", ageDays = 40)
        val recent = file("game-output-pack-2026-09-20.log", ageDays = 8)

        sweep()

        assertFalse(Files.exists(old))
        assertTrue(Files.exists(recent))
    }

    @Test
    fun `over the size limit the oldest go first until it fits`() {
        val a = file("game-output-a.log", ageDays = 3, bytes = 400)
        val b = file("game-output-b.log", ageDays = 2, bytes = 400)
        val c = file("game-output-c.log", ageDays = 1, bytes = 400)

        sweep(maxBytes = 900)

        assertFalse(Files.exists(a), "the oldest should have gone")
        assertTrue(Files.exists(b))
        assertTrue(Files.exists(c))
    }

    @Test
    fun `a stale tmp goes, one being written stays`() {
        val stale = file("launcher-2026-07-17.0.log67893272279136.tmp", ageDays = 70)
        val fresh = file("launcher-2026-09-28.0.log123.tmp", ageDays = 0, ageMinutes = 5)

        sweep()

        assertFalse(Files.exists(stale))
        assertTrue(Files.exists(fresh))
    }

    @Test
    fun `logback's own files and the player's exports are left alone`() {
        val kept = listOf(
            file("launcher.log", ageDays = 90),
            file("launcher-2026-05-24.0.log.gz", ageDays = 90),
            file("game.log", ageDays = 90),
            file("crash-2026-08-31.0.log.gz", ageDays = 90),
            file("console-export-2026-05-30_22-34-03.log", ageDays = 90),
        )

        val (removed, _) = sweep(maxBytes = 0)

        assertEquals(0, removed)
        kept.forEach { assertTrue(Files.exists(it), "$it was removed") }
    }

    @Test
    fun `a session written a moment ago is never removed, whatever the limits say`() {
        val live = file("game-output-live.log", ageDays = 0, bytes = 1000)

        sweep(maxBytes = 0)

        assertTrue(Files.exists(live))
    }

    @Test
    fun `a missing directory is not an error`() {
        assertEquals(0 to 0L, LogRetention(dir.resolve("absent"), now = { now }).sweep())
    }
}
