package hivens.launcher.platform

import org.slf4j.LoggerFactory
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * Single-instance gate via OS-level advisory file lock.
 *
 * Why this exists as its own object instead of inline in Main.kt:
 *   - the lock channel and the [FileLock] handle must outlive the calling
 *     scope. As local variables they would technically survive while
 *     `application { }` blocks, but storing them on a static (object) field
 *     removes any theoretical GC-eligibility ambiguity.
 *   - a shutdown hook flushes the lock cleanly and logs release; without it,
 *     the JVM still cleans up on exit but we lose the audit trail when
 *     diagnosing "two instances running" complaints.
 *   - the lock file carries the holder's PID so a stuck process can be
 *     identified by inspection (`cat ~/.local/share/nexira/.lock`).
 *
 * Failure mode is **fail-open**: if lock acquisition itself crashes for
 * an unexpected reason (FS oddities, permissions), we log the warning
 * and return true -- better a possibly-double instance than no launcher
 * startup at all. The corresponding correctness concern (double migration
 * race) is now defused because [DataDirMigration] runs *after* this gate.
 */
object SingleInstance {
    private val log = LoggerFactory.getLogger(SingleInstance::class.java)

    @Volatile private var heldChannel: FileChannel? = null
    @Volatile private var heldLock: FileLock? = null

    /**
     * Returns true if this process now owns the single-instance lock,
     * false if another instance already holds it. On false, a `.show`
     * signal file is dropped in [dataDir] so the running instance's
     * watcher can raise its window.
     */
    fun acquire(dataDir: Path): Boolean {
        val lockFile = dataDir.resolve(".lock")
        return try {
            val channel = FileChannel.open(
                lockFile,
                StandardOpenOption.CREATE,
                StandardOpenOption.READ,
                StandardOpenOption.WRITE,
            )
            val lock = try {
                channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                // Same-JVM overlap. The cross-process equivalent returns null
                // from tryLock(); we collapse both to the "another holder"
                // path so the test harness can simulate the conflict from
                // within a single JVM and the production path stays correct.
                channel.close()
                writeShowSignal(dataDir)
                log.info("Single-instance lock held in this JVM already; .show signal written")
                return false
            }
            if (lock == null) {
                channel.close()
                writeShowSignal(dataDir)
                log.info("Single-instance lock held by another process; .show signal written")
                false
            } else {
                heldChannel = channel
                heldLock = lock
                writePid(dataDir)
                Runtime.getRuntime().addShutdownHook(Thread(::release, "single-instance-release"))
                log.info("Single-instance lock acquired (pid={})", ProcessHandle.current().pid())
                true
            }
        } catch (e: Exception) {
            log.warn("Lock acquisition failed; failing open to avoid bricking startup", e)
            true
        }
    }

    /**
     * Idempotent release -- safe to call from a shutdown hook *and* from
     * an explicit teardown path; the second call is a no-op.
     */
    fun release() {
        val channel = heldChannel
        val lock = heldLock
        heldLock = null
        heldChannel = null
        runCatching { lock?.release() }
        runCatching { channel?.close() }
        if (channel != null) log.info("Single-instance lock released")
    }

    private fun writeShowSignal(dataDir: Path) {
        runCatching {
            val show = dataDir.resolve(".show")
            // Atomic create -- the prior `if (!Files.exists) createFile` was
            // a TOCTOU window: two launchers started in the same millisecond
            // could both observe the file missing and one would then throw
            // FileAlreadyExistsException (silently swallowed by runCatching),
            // potentially losing the "raise window" intent on the watcher
            // side. createFile + ignore-if-exists collapses the race.
            try {
                Files.createFile(show)
            } catch (_: FileAlreadyExistsException) {
                // Already signalled by a sibling launcher attempt -- fine,
                // the running instance's watcher will pick it up either way.
            }
        }
    }

    /**
     * PID is written to a SEPARATE `.lock.pid` file rather than into `.lock`
     * itself because Windows treats `FileChannel.tryLock()` as a mandatory
     * OS-level lock -- once acquired, no other handle (even in the same JVM)
     * can open the file for read, and `Files.readString(.lock)` throws
     * IOException. Linux and macOS use advisory locks where reads work
     * fine, but cross-platform consistency wins. `.lock` stays empty and
     * sole-purpose; `.lock.pid` is informational and freely readable for
     * `cat ~/.local/share/nexira/.lock.pid` debugging.
     */
    private fun writePid(dataDir: Path) {
        runCatching {
            Files.writeString(dataDir.resolve(".lock.pid"), "${ProcessHandle.current().pid()}\n")
        }
    }
}
