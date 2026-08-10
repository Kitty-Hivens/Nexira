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
 * AppImage is a single executable; we back the current one up, move the
 * downloaded version into place, set +x, relaunch, and on rollback restore
 * the backup. Desktop-shortcut paths are rewritten if the AppImage filename
 * changed (because a new version moved from `Nexira-2.3.0-x86_64.AppImage`
 * to `Nexira-2.3.1-x86_64.AppImage`).
 *
 * The complexity here vs Windows / macOS comes from rollback: the old
 * AppImage is preserved as `<exe>.backup` until the new one proves it
 * starts; failure restores the backup and re-relaunches it.
 *
 * All of that happens in a shutdown hook, so it runs with the window already
 * gone and nothing able to report it -- which is why neither half moves the
 * image around. [stagingPath] has the download written where the install is a
 * rename, and the backup is a second name for the bytes already on disk.
 */
class LinuxUpdateApplicator : IUpdateApplicator {
    private val logger = LoggerFactory.getLogger(LinuxUpdateApplicator::class.java)

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
        val target = targetFor(currentExe, fileName)
        val dir = target.parent ?: return fallbackDir.resolve(fileName)
        return if (Files.isWritable(dir)) stagedFor(target) else fallbackDir.resolve(fileName)
    }

    override fun stagedLeftovers(): List<Path> {
        val dir = runCatching { resolveExecutable().parent }.getOrNull() ?: return emptyList()
        return leftoversIn(dir)
    }

    /** Split out so the sweep is testable without an installed launcher. */
    internal fun leftoversIn(dir: Path): List<Path> = try {
        Files.list(dir).use { stream ->
            stream.filter { it.fileName.toString().endsWith("$APPIMAGE_EXT$STAGED_SUFFIX", ignoreCase = true) }
                .toList()
        }
    } catch (e: Exception) {
        logger.debug("Could not sweep staged updates in {}", dir, e)
        emptyList()
    }

    override fun scheduleUpdate(installerPath: Path) {
        try {
            val currentExe = resolveExecutable()
            val targetExe = targetFor(currentExe, assetNameOf(installerPath))

            val backupPath = Paths.get("$currentExe.backup")

            logger.info("Scheduled Linux update: {} -> {}", currentExe, targetExe)

            Runtime.getRuntime().addShutdownHook(Thread {
                try {
                    logger.info("Applying Linux update...")

                    swapBinary(installerPath, currentExe, targetExe, backupPath)

                    if (currentExe != targetExe) {
                        updateDesktopShortcuts(currentExe, targetExe)
                    }

                    // Relaunch
                    logger.info("Relaunching updated version...")
                    val process = ProcessBuilder(targetExe.toString()).start()

                    // Cleanup
                    Thread.sleep(2000)
                    if (process.isAlive) {
                        Files.deleteIfExists(backupPath)
                        Files.deleteIfExists(installerPath)
                        if (currentExe != targetExe) {
                            Files.deleteIfExists(currentExe)
                        }
                        logger.info("Update completed successfully")
                    } else {
                        logger.error("New version failed to start, rolling back...")
                        if (currentExe != targetExe) {
                            Files.deleteIfExists(targetExe)
                            updateDesktopShortcuts(targetExe, currentExe)
                        }
                        restoreBackup(backupPath, currentExe)
                        ProcessBuilder(currentExe.toString()).start()
                    }
                } catch (e: Exception) {
                    logger.error("Update failed, attempting rollback", e)
                    try {
                        if (Files.exists(backupPath)) {
                            if (currentExe != targetExe) {
                                updateDesktopShortcuts(targetExe, currentExe)
                            }
                            restoreBackup(backupPath, currentExe)
                            ProcessBuilder(currentExe.toString()).start()
                        }
                    } catch (rollbackEx: Exception) {
                        logger.error("Rollback failed!", rollbackEx)
                    }
                }
            })
        } catch (e: Exception) {
            logger.error("Failed to schedule Linux update", e)
            throw e
        }
    }

    /**
     * Puts the downloaded AppImage at [targetExe], keeping [currentExe] runnable
     * throughout.
     *
     * Order matters more than it looks. Moving the live binary to [backupPath]
     * first -- which is what this did -- leaves nothing at the launcher's path
     * until the copy lands, and this whole sequence runs inside a shutdown
     * hook: a reboot, a logout that cuts the hook short, or a kill in that
     * window leaves the user with a `.backup` file, no launcher, and nothing
     * still running to restore it. The rollback below only helps while the
     * process is alive to run it.
     *
     * So: stage the new image beside the target, back up without moving it, and
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
    internal fun swapBinary(installerPath: Path, currentExe: Path, targetExe: Path, backupPath: Path) {
        // Beside the target, so the move below stays on one filesystem and can
        // be atomic. A leftover from an interrupted attempt is overwritten.
        val staged = stagedFor(targetExe)
        if (installerPath != staged) {
            Files.copy(installerPath, staged, StandardCopyOption.REPLACE_EXISTING)
            logger.info("Staged new version at {}", staged)
        }
        setExecutable(staged)

        if (Files.exists(currentExe)) {
            backUp(currentExe, backupPath)
            logger.info("Backed up current version")
        }

        try {
            Files.move(staged, targetExe, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            // Some filesystems refuse the atomic flag. The replacing move is
            // still a single operation as far as this process is concerned.
            Files.move(staged, targetExe, StandardCopyOption.REPLACE_EXISTING)
        }
        setExecutable(targetExe)
        logger.info("Installed new version at {}", targetExe)
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
     * The two are the same file whenever the update was installed under a new
     * name: the backup is a link, and the binary it links to was never touched.
     * A rename between two names of one inode succeeds and does nothing, which
     * would leave the `.backup` sitting beside the launcher for good -- so drop
     * the extra name instead, which is the whole of the restore in that case.
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

    private fun updateDesktopShortcuts(oldExe: Path, newExe: Path) {
        try {
            val userHome = System.getProperty("user.home") ?: return
            val applicationsDir = Paths.get(userHome, ".local", "share", "applications")

            if (!Files.exists(applicationsDir)) return

            Files.list(applicationsDir).use { stream ->
                stream.forEach { desktopFile ->
                    if (desktopFile.toString().endsWith(".desktop")) {
                        try {
                            val content = Files.readString(desktopFile)
                            if (content.contains(oldExe.toString())) {
                                val updatedContent = content.replace(oldExe.toString(), newExe.toString())
                                Files.writeString(desktopFile, updatedContent)
                                logger.info("Updated desktop shortcut: {}", desktopFile.fileName)
                            }
                        } catch (e: Exception) {
                            logger.warn("Failed to update desktop file: {}", desktopFile.fileName, e)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to scan for desktop shortcuts", e)
        }
    }

    /**
     * The asset's own name, with the staging suffix taken back off. The target
     * is decided by what was published, and a path this class chose for the
     * download must not change that decision.
     */
    internal fun assetNameOf(installerPath: Path): String =
        installerPath.fileName.toString().removeSuffix(STAGED_SUFFIX)

    /**
     * Where [assetName] installs to. A published AppImage carries its version in
     * the file name, so the update generally lands beside the running one under
     * a new name; anything else replaces the binary in place.
     */
    internal fun targetFor(currentExe: Path, assetName: String): Path =
        if (assetName.contains("Nexira", ignoreCase = true) &&
            assetName.endsWith(APPIMAGE_EXT, ignoreCase = true)
        ) {
            currentExe.resolveSibling(assetName)
        } else {
            currentExe
        }

    private fun stagedFor(targetExe: Path): Path =
        targetExe.resolveSibling("${targetExe.fileName}$STAGED_SUFFIX")

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

    private companion object {
        const val APPIMAGE_EXT = ".AppImage"
        /** Marks a download that is not yet the launcher. Stripped before the target is decided. */
        const val STAGED_SUFFIX = ".new"
    }
}
