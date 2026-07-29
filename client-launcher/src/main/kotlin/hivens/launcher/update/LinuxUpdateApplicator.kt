package hivens.launcher.update

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
 * AppImage is a single executable; we back the current one up, copy the
 * downloaded version into place, set +x, relaunch, and on rollback restore
 * the backup. Desktop-shortcut paths are rewritten if the AppImage filename
 * changed (because a new version moved from `Nexira-2.3.0-x86_64.AppImage`
 * to `Nexira-2.3.1-x86_64.AppImage`).
 *
 * The complexity here vs Windows / macOS comes from rollback: the old
 * AppImage is preserved as `<exe>.backup` until the new one proves it
 * starts; failure restores the backup and re-relaunches it.
 */
class LinuxUpdateApplicator : IUpdateApplicator {
    private val logger = LoggerFactory.getLogger(LinuxUpdateApplicator::class.java)

    override fun scheduleUpdate(installerPath: Path) {
        try {
            val currentExe = resolveExecutable()
            val downloadedFileName = installerPath.fileName.toString()
            val targetExe = if (downloadedFileName.contains("Nexira", ignoreCase = true) &&
                downloadedFileName.endsWith(".AppImage", ignoreCase = true)
            ) {
                currentExe.resolveSibling(downloadedFileName)
            } else {
                currentExe
            }

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
                        Files.move(backupPath, currentExe, StandardCopyOption.REPLACE_EXISTING)
                        ProcessBuilder(currentExe.toString()).start()
                    }
                } catch (e: Exception) {
                    logger.error("Update failed, attempting rollback", e)
                    try {
                        if (Files.exists(backupPath)) {
                            if (currentExe != targetExe) {
                                updateDesktopShortcuts(targetExe, currentExe)
                            }
                            Files.move(backupPath, currentExe, StandardCopyOption.REPLACE_EXISTING)
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
     * So: stage the new image beside the target, back up by COPY rather than
     * move, and swap it in with a single move. Every failure before that move
     * leaves the installed launcher exactly as it was, and the move itself
     * replaces one complete file with another.
     */
    internal fun swapBinary(installerPath: Path, currentExe: Path, targetExe: Path, backupPath: Path) {
        // Beside the target, so the move below stays on one filesystem and can
        // be atomic. A leftover from an interrupted attempt is overwritten.
        val staged = targetExe.resolveSibling("${targetExe.fileName}.new")
        Files.copy(installerPath, staged, StandardCopyOption.REPLACE_EXISTING)
        setExecutable(staged)
        logger.info("Staged new version at {}", staged)

        if (Files.exists(currentExe)) {
            Files.copy(currentExe, backupPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
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
}
