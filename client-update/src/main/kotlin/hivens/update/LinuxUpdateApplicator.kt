package hivens.update

import hivens.core.api.interfaces.IUpdateApplicator
import org.slf4j.LoggerFactory
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission

/**
 * Linux (AppImage) update flow.
 *
 * AppImage is a single executable, and an update replaces the one the launcher
 * was started from, under the name it already has. The published file name
 * decides what is downloaded, never where it goes. Whatever tracks the file by
 * its path keeps finding it: a menu entry, a store that renamed it on install,
 * an integrator that moved it into its own folder. A nightly kept beside a
 * release is never touched by the release's update, nor the other way round.
 *
 * The current binary is backed up before the swap, and a new version that fails
 * to start is rolled back and the old one relaunched.
 *
 * The swap happens in a shutdown hook, so it runs with the window already gone
 * and nothing able to report it -- which is why neither half moves the image
 * around. [stagingPath] has the download written where the install is a rename,
 * and the backup is a second name for the bytes already on disk.
 *
 * Whether the new version starts is not decided in the hook. Nothing the hook can
 * observe in the moments before the JVM exits says that: a build that shows its
 * window and dies a few seconds later looks alive to any check made from here. So
 * the hook leaves a [pendingFor] marker and hands over to [WATCHDOG_SCRIPT], a
 * shell process that outlives this JVM, starts the new version once this one is
 * gone, and puts the backup back if it exits before [confirmStarted] has cleared
 * the marker.
 */
class LinuxUpdateApplicator : IUpdateApplicator {
    private val logger = LoggerFactory.getLogger(LinuxUpdateApplicator::class.java)
    private val scheduled = ScheduledInstall()

    /**
     * The download lands here, beside the binary it will replace, so the install
     * is a rename rather than a copy of the whole image.
     *
     * Only where that directory is writable. A launcher installed somewhere the
     * user cannot write falls back to the updates directory and pays for the
     * copy -- the update still works, which is the point of asking rather than
     * assuming.
     */
    override fun stagingPath(fallbackDir: Path, fileName: String): Path {
        val currentExe = runCatching { resolveExecutable() }.getOrNull()
            ?: return fallbackDir.resolve(fileName)
        return stagedPathFor(currentExe, fallbackDir, fileName)
    }

    /** Split out so the decision is testable without an installed launcher. */
    internal fun stagedPathFor(currentExe: Path, fallbackDir: Path, fileName: String): Path {
        val dir = currentExe.parent ?: return fallbackDir.resolve(fileName)
        return if (Files.isWritable(dir)) stagedFor(currentExe) else fallbackDir.resolve(fileName)
    }

    override fun stagedLeftovers(): List<Path> {
        val exe = runCatching { resolveExecutable() }.getOrNull() ?: return emptyList()
        val dir = exe.parent ?: return emptyList()
        return leftoversIn(dir, exe.fileName.toString())
    }

    /**
     * Staged downloads in [dir]: the one for the running binary, [exeName], which
     * may be named anything, and any `*.AppImage.new` an older build staged under
     * the name of the release it was downloading. Split out so the sweep is
     * testable without an installed launcher.
     */
    internal fun leftoversIn(dir: Path, exeName: String): List<Path> = try {
        Files.list(dir).use { stream ->
            stream.filter {
                val name = it.fileName.toString()
                name == "$exeName$STAGED_SUFFIX" ||
                    name.endsWith("$APPIMAGE_EXT$STAGED_SUFFIX", ignoreCase = true)
            }.toList()
        }
    } catch (e: Exception) {
        logger.debug("Could not sweep staged updates in {}", dir, e)
        emptyList()
    }

    override fun scheduleUpdate(installerPath: Path) {
        try {
            val exe = resolveExecutable()
            val backupPath = backupFor(exe)

            logger.info("Scheduled Linux update of {}", exe)

            scheduled.replace("launcher-update-install") {
                try {
                    logger.info("Applying Linux update...")
                    swapBinary(installerPath, exe, backupPath)
                    // Before the watchdog starts, so the new version can never run
                    // without it: a marker it has not cleared is what says it did
                    // not start.
                    Files.deleteIfExists(pendingFor(exe))
                    Files.createFile(pendingFor(exe))
                    logger.info("Updated version is on probation until it confirms it started")
                } catch (e: Exception) {
                    logger.error("Update failed, restoring the installed version", e)
                    runCatching { if (Files.exists(backupPath)) restoreBackup(backupPath, exe) }
                        .onFailure { logger.error("Rollback failed!", it) }
                    runCatching { Files.deleteIfExists(pendingFor(exe)) }
                }
                // Either way something has to come back up, and only once this JVM is
                // gone: it still holds the single-instance lock while its hooks run,
                // and a launcher started now would find the lock taken and quit.
                try {
                    startWatchdog(exe, backupPath, installerPath)
                } catch (e: Exception) {
                    logger.error("Could not start the update watchdog; the launcher has to be reopened by hand", e)
                }
            }
        } catch (e: Exception) {
            logger.error("Failed to schedule Linux update", e)
            throw e
        }
    }

    /**
     * Ends the probation an update left behind: the marker goes, and with it the
     * backup the watchdog would otherwise have put back. A launcher started with
     * no update pending has nothing here and leaves an unrelated backup alone.
     */
    override fun confirmStarted() {
        val exe = runCatching { resolveExecutable() }.getOrNull() ?: return
        if (System.getenv(ROLLED_BACK_ENV) != null) {
            logger.warn("The update installed last time did not start and was rolled back to this version")
        }
        runCatching { confirmFor(exe) }
            .onFailure { logger.warn("Could not clear the update probation of {}", exe, it) }
    }

    /** Split out so the confirmation is testable without an installed launcher. */
    internal fun confirmFor(exe: Path) {
        // The marker first: once it is gone the watchdog no longer acts, so a
        // backup that outlives a failed delete below is clutter, never a rollback
        // of a version that has already proven itself.
        if (!Files.deleteIfExists(pendingFor(exe))) return
        Files.deleteIfExists(backupFor(exe))
        logger.info("Updated version confirmed it started; backup removed")
    }

    private fun startWatchdog(exe: Path, backupPath: Path, installerPath: Path) {
        ProcessBuilder("/bin/sh", "-c", WATCHDOG_SCRIPT)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .apply {
                environment().apply {
                    // Paths travel as variables, so nothing a path may contain can
                    // escape the script's quoting.
                    put("EXE", exe.toString())
                    put("BACKUP", backupPath.toString())
                    put("PENDING", pendingFor(exe).toString())
                    put("INSTALLER", installerPath.toString())
                    put("OLD_PID", ProcessHandle.current().pid().toString())
                    put("GRACE", PROBATION_GRACE_SECONDS.toString())
                    // One-shot, as in AppRelauncher: carried over it would boot the
                    // updated launcher straight into recovery.
                    remove("NEXIRA_RECOVERY")
                    remove(ROLLED_BACK_ENV)
                }
            }
            .start()
    }

    /**
     * Puts the downloaded AppImage at [exe], keeping it runnable throughout.
     *
     * Order matters more than it looks. Moving the live binary to [backupPath]
     * first -- which is what this did -- leaves nothing at the launcher's path
     * until the copy lands, and this whole sequence runs inside a shutdown
     * hook: a reboot, a logout that cuts the hook short, or a kill in that
     * window leaves the user with a `.backup` file, no launcher, and nothing
     * still running to restore it. The rollback below only helps while the
     * process is alive to run it.
     *
     * So: stage the new image beside the binary, back up without moving it, and
     * swap it in with a single move. Every failure before that move leaves the
     * installed launcher exactly as it was, and the move itself replaces one
     * complete file with another.
     *
     * Duration matters too, because this runs with the process already told to
     * exit. Both halves are arranged to cost nothing: the download is written
     * straight to the staging path where it can be reached (see [stagingPath]),
     * so there is no image to copy, and the backup is a second name for the same
     * bytes rather than a second copy of them.
     */
    internal fun swapBinary(installerPath: Path, exe: Path, backupPath: Path) {
        // Beside the binary, so the move below stays on one filesystem and can
        // be atomic. A leftover from an interrupted attempt is overwritten.
        val staged = stagedFor(exe)
        if (installerPath != staged) {
            Files.copy(installerPath, staged, StandardCopyOption.REPLACE_EXISTING)
            logger.info("Staged new version at {}", staged)
        }
        setExecutable(staged)

        if (Files.exists(exe)) {
            backUp(exe, backupPath)
            logger.info("Backed up current version")
        }

        try {
            Files.move(staged, exe, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            // Some filesystems refuse the atomic flag. The replacing move is
            // still a single operation as far as this process is concerned.
            Files.move(staged, exe, StandardCopyOption.REPLACE_EXISTING)
        }
        setExecutable(exe)
        logger.info("Installed new version at {}", exe)
    }

    /**
     * A hard link, so the backup costs one directory entry instead of a second
     * copy of the image -- the swap below replaces the name, never the inode the
     * link holds, and the rollback moves it back.
     *
     * Filesystems that refuse links (FAT among them, and any crossing of a mount
     * point) fall back to copying, which is what this always did.
     */
    private fun backUp(currentExe: Path, backupPath: Path) {
        Files.deleteIfExists(backupPath)
        try {
            Files.createLink(backupPath, currentExe)
        } catch (e: Exception) {
            logger.debug("Hard link refused for {}, copying instead", backupPath, e)
            Files.copy(currentExe, backupPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
        }
    }

    /**
     * Puts the backed-up launcher back at [currentExe].
     *
     * The two are still the same file when the swap never happened: the backup
     * is a link, and the binary it links to was not replaced. A rename between
     * two names of one inode succeeds and does nothing, which would leave the
     * `.backup` sitting beside the launcher for good. Dropping the extra name is
     * the whole of the restore in that case.
     */
    internal fun restoreBackup(backupPath: Path, currentExe: Path) {
        if (Files.exists(currentExe) && Files.isSameFile(backupPath, currentExe)) {
            Files.deleteIfExists(backupPath)
            return
        }
        Files.move(backupPath, currentExe, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun setExecutable(path: Path) {
        try {
            Files.setPosixFilePermissions(path, setOf(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ,
                PosixFilePermission.GROUP_EXECUTE,
                PosixFilePermission.OTHERS_READ,
                PosixFilePermission.OTHERS_EXECUTE
            ))
        } catch (_: UnsupportedOperationException) {
            path.toFile().setExecutable(true)
        }
    }

    private fun stagedFor(exe: Path): Path =
        exe.resolveSibling("${exe.fileName}$STAGED_SUFFIX")

    private fun backupFor(exe: Path): Path = exe.resolveSibling("${exe.fileName}$BACKUP_SUFFIX")

    /** Present from the swap until the new version confirms it started. */
    internal fun pendingFor(exe: Path): Path = exe.resolveSibling("${exe.fileName}$PENDING_SUFFIX")

    private fun resolveExecutable(): Path {
        // When running as AppImage, the runtime automatically sets $APPIMAGE
        // to the real path of the .AppImage file on disk.
        //
        // DO NOT use /proc/self/exe naively -- under AppImage it resolves to the
        // temporary FUSE mount point (/tmp/.mount_NexirXXXXX/usr/bin/Nexira)
        // which is gone the moment the process exits.
        val appImageEnv = System.getenv("APPIMAGE")
        if (!appImageEnv.isNullOrBlank()) return Paths.get(appImageEnv)
        return try {
            Paths.get("/proc/self/exe").toRealPath()
        } catch (e: Exception) {
            val classPath = System.getProperty("java.class.path")
                ?: error("Cannot resolve Linux launcher binary: APPIMAGE unset, /proc/self/exe failed (${e.message}), java.class.path is null")
            Paths.get(classPath.split(":").first()).toAbsolutePath()
        }
    }

    internal companion object {
        const val APPIMAGE_EXT = ".AppImage"
        /** Marks a download that is not yet the launcher. */
        const val STAGED_SUFFIX = ".new"
        const val BACKUP_SUFFIX = ".backup"
        const val PENDING_SUFFIX = ".update-pending"

        /** Set on the old version the watchdog relaunches after a rollback. */
        const val ROLLED_BACK_ENV = "NEXIRA_UPDATE_ROLLED_BACK"

        /**
         * How long a new version that never confirms has to stay up to be kept.
         *
         * The confirmation is the real test. This covers a target that cannot
         * give it: a release older than the confirmation, installed by picking
         * that version in the update manager, runs fine and would otherwise be
         * rolled back the first time it is closed.
         */
        const val PROBATION_GRACE_SECONDS = 60

        /**
         * Runs after the hook, outside the JVM, with its paths in the environment.
         *
         * It waits for `OLD_PID` to go before it starts anything, because that
         * process holds the single-instance lock until it is gone. With no
         * `PENDING` marker the swap did not happen, and the launcher on disk is
         * simply started again. With one, the new version runs in the foreground
         * while a timer ends its probation after `GRACE` seconds, and if it exits
         * with the marker still there, it never confirmed and never outlasted the
         * timer: the backup goes back and the old version is started with
         * [ROLLED_BACK_ENV] set.
         *
         * `-ef` covers a backup that is still the launcher's own inode, which is
         * the case [restoreBackup] describes.
         */
        val WATCHDOG_SCRIPT = $$"""
            while kill -0 "$OLD_PID" 2>/dev/null; do sleep 1; done
            rm -f "$INSTALLER"
            if [ ! -e "$PENDING" ]; then exec "$EXE"; fi
            ( sleep "$GRACE"; rm -f "$PENDING" "$BACKUP" ) &
            timer=$!
            "$EXE"
            kill "$timer" 2>/dev/null
            if [ -e "$PENDING" ]; then
                rm -f "$PENDING"
                if [ -e "$BACKUP" ]; then
                    if [ "$BACKUP" -ef "$EXE" ]; then rm -f "$BACKUP"; else mv -f "$BACKUP" "$EXE"; fi
                    $$ROLLED_BACK_ENV=1 exec "$EXE"
                fi
            fi
        """.trimIndent()
    }
}
