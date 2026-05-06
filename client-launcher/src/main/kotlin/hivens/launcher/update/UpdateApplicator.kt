package hivens.launcher.update

import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission

object UpdateApplicator {
    private val logger = LoggerFactory.getLogger(UpdateApplicator::class.java)

    /**
     * Schedules the update to be applied when exiting the launcher.
     */
    fun scheduleUpdate(installerPath: Path) {
        when {
            OS.isWindows -> scheduleWindowsUpdate(installerPath)
            OS.isMacOS -> scheduleMacUpdate(installerPath)
            OS.isLinux -> scheduleLinuxUpdate(installerPath)
            else -> throw UnsupportedOperationException("Update not supported on ${OS.getName()}")
        }
    }

    // ========== WINDOWS ==========

    private fun scheduleWindowsUpdate(installerPath: Path) {
        try {
            val psScript = Files.createTempFile("aura_update", ".ps1")
            val launcherPath = getCurrentExecutable()

            psScript.toFile().writeText("""
                # Wait for launcher to exit
                Start-Sleep -Seconds 2
                
                # Ensure all processes are closed
                Get-Process -Name "AuraLauncher" -ErrorAction SilentlyContinue | Stop-Process -Force
                
                # Run installer silently (Inno Setup /SILENT flag)
                Write-Host "Installing update..."
                Start-Process -FilePath "$installerPath" -ArgumentList "/SILENT", "/NORESTART" -Wait
                
                # Wait for installation
                Start-Sleep -Seconds 3
                
                # Launch new version
                if (Test-Path "$launcherPath") {
                    Write-Host "Launching updated launcher..."
                    Start-Process "$launcherPath"
                } else {
                    Write-Error "Launcher executable not found at $launcherPath"
                }
                
                # Cleanup
                Start-Sleep -Seconds 2
                Remove-Item "$psScript" -Force -ErrorAction SilentlyContinue
                Remove-Item "$installerPath" -Force -ErrorAction SilentlyContinue
            """.trimIndent())

            logger.info("Scheduled Windows update: $installerPath")

            Runtime.getRuntime().addShutdownHook(Thread {
                try {
                    ProcessBuilder(
                        "powershell.exe",
                        "-ExecutionPolicy", "Bypass",
                        "-WindowStyle", "Hidden",
                        "-File", psScript.toString()
                    ).start()
                } catch (e: Exception) {
                    logger.error("Failed to execute update script", e)
                }
            })

        } catch (e: Exception) {
            logger.error("Failed to schedule Windows update", e)
            throw e
        }
    }

    // ========== MACOS ==========

    private fun scheduleMacUpdate(dmgPath: Path) {
        try {
            val scriptPath = Files.createTempFile("aura_update", ".sh")
            val currentBinary = getCurrentExecutable()
            val currentAppBundle = currentBinary.parent?.parent?.parent ?: Paths.get("/Applications/AuraLauncher.app")
            val targetDir = currentAppBundle.parent ?: Paths.get("/Applications")

            scriptPath.toFile().writeText("""
                #!/bin/bash
                set -e
                
                # Wait for launcher to exit
                sleep 2
                
                # Kill any remaining processes
                killall -9 AuraLauncher 2>/dev/null || true
                
                # Mount DMG
                echo "Mounting update image..."
                hdiutil attach "$dmgPath" -mountpoint /Volumes/AuraUpdate -nobrowse -quiet || exit 1
                
                if [ ! -d "/Volumes/AuraUpdate/AuraLauncher.app" ]; then
                    echo "Error: AuraLauncher.app not found in DMG"
                    hdiutil detach /Volumes/AuraUpdate -quiet || true
                    exit 1
                fi
                
                # Remove old version
                echo "Removing old version..."
                rm -rf "$currentAppBundle" || true
                
                # Copy new version
                echo "Installing new version..."
                cp -R /Volumes/AuraUpdate/AuraLauncher.app "$targetDir/"
                
                # Unmount DMG
                echo "Cleaning up..."
                hdiutil detach /Volumes/AuraUpdate -quiet
                rm -f "$dmgPath"
                
                # Relaunch
                echo "Launching updated version..."
                sleep 1
                open "$targetDir/AuraLauncher.app"
                
                # Cleanup script
                rm -f "$scriptPath"
            """.trimIndent())

            scriptPath.toFile().setExecutable(true)
            logger.info("Scheduled macOS update: $dmgPath targeting $targetDir")

            Runtime.getRuntime().addShutdownHook(Thread {
                try {
                    ProcessBuilder("bash", scriptPath.toString()).start()
                } catch (e: Exception) {
                    logger.error("Failed to execute update script", e)
                }
            })

        } catch (e: Exception) {
            logger.error("Failed to schedule macOS update", e)
            throw e
        }
    }

    // ========== LINUX ==========

    private fun scheduleLinuxUpdate(appImagePath: Path) {
        try {
            val currentExe = getCurrentExecutable()
            val downloadedFileName = appImagePath.fileName.toString()
            val targetExe = if (downloadedFileName.contains("AuraLauncher", ignoreCase = true) && downloadedFileName.endsWith(".AppImage", ignoreCase = true)) {
                currentExe.resolveSibling(downloadedFileName)
            } else {
                currentExe
            }

            val backupPath = Paths.get("$currentExe.backup")

            logger.info("Scheduled Linux update: $currentExe -> $targetExe")

            Runtime.getRuntime().addShutdownHook(Thread {
                try {
                    logger.info("Applying Linux update...")

                    // Backup current version
                    if (Files.exists(currentExe)) {
                        Files.move(currentExe, backupPath, StandardCopyOption.REPLACE_EXISTING)
                        logger.info("Backed up current version")
                    }

                    // Copy new version to the target path
                    Files.copy(appImagePath, targetExe, StandardCopyOption.REPLACE_EXISTING)
                    logger.info("Copied new version to $targetExe")

                    // Set executable permissions
                    try {
                        Files.setPosixFilePermissions(targetExe, setOf(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OWNER_EXECUTE,
                            PosixFilePermission.GROUP_READ,
                            PosixFilePermission.GROUP_EXECUTE,
                            PosixFilePermission.OTHERS_READ,
                            PosixFilePermission.OTHERS_EXECUTE
                        ))
                    } catch (_: UnsupportedOperationException) {
                        targetExe.toFile().setExecutable(true)
                    }
                    logger.info("Set executable permissions")

                    if (currentExe != targetExe) {
                        updateLinuxDesktopShortcuts(currentExe, targetExe)
                    }

                    // Relaunch
                    logger.info("Relaunching updated version...")
                    val process = ProcessBuilder(targetExe.toString()).start()

                    // Cleanup
                    Thread.sleep(2000)
                    if (process.isAlive) {
                        Files.deleteIfExists(backupPath)
                        Files.deleteIfExists(appImagePath)
                        if (currentExe != targetExe) {
                            Files.deleteIfExists(currentExe)
                        }
                        logger.info("Update completed successfully")
                    } else {
                        // Rollback on failure
                        logger.error("New version failed to start, rolling back...")
                        if (currentExe != targetExe) {
                            Files.deleteIfExists(targetExe)
                            updateLinuxDesktopShortcuts(targetExe, currentExe)
                        }
                        Files.move(backupPath, currentExe, StandardCopyOption.REPLACE_EXISTING)
                        ProcessBuilder(currentExe.toString()).start()
                    }

                } catch (e: Exception) {
                    logger.error("Update failed, attempting rollback", e)
                    try {
                        if (Files.exists(backupPath)) {
                            if (currentExe != targetExe) {
                                updateLinuxDesktopShortcuts(targetExe, currentExe)
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

    private fun updateLinuxDesktopShortcuts(oldExe: Path, newExe: Path) {
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
                                logger.info("Updated desktop shortcut: ${desktopFile.fileName}")
                            }
                        } catch (e: Exception) {
                            logger.warn("Failed to update desktop file: ${desktopFile.fileName}", e)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            logger.warn("Failed to scan for desktop shortcuts", e)
        }
    }

    // ========== HELPERS ==========

    private fun getCurrentExecutable(): Path = when {
        OS.isWindows -> resolveWindowsExecutable()
        OS.isMacOS   -> resolveMacOsExecutable()
        OS.isLinux   -> resolveLinuxExecutable()
        else -> error("Unsupported platform: ${OS.getName()}; cannot locate launcher binary")
    }

    private fun resolveWindowsExecutable(): Path {
        val classPath = System.getProperty("java.class.path")
            ?: error("java.class.path is unset; cannot locate Windows launcher binary")
        val jarPath = Paths.get(classPath.split(";").first()).toAbsolutePath()
        val installRoot = jarPath.parent?.parent
            ?: error("Cannot resolve Windows install root from $jarPath; expected lib/<jar>.jar inside install dir")
        return installRoot.resolve("AuraLauncher.exe")
    }

    private fun resolveMacOsExecutable(): Path {
        // .app/Contents/app/<jar>.jar  ->  .app/Contents/MacOS/AuraLauncher
        val classPath = System.getProperty("java.class.path")
        if (!classPath.isNullOrBlank()) {
            val jarPath = Paths.get(classPath.split(":").first()).toAbsolutePath()
            val contents = jarPath.parent?.parent
            if (contents != null) return contents.resolve("MacOS").resolve("AuraLauncher")
        }
        error("Cannot resolve macOS launcher binary: java.class.path missing or not in expected .app/Contents/app/ layout")
    }

    private fun resolveLinuxExecutable(): Path {
        // When running as AppImage, the runtime automatically sets $APPIMAGE
        // to the real path of the .AppImage file on disk.
        //
        // DO NOT use /proc/self/exe naively — under AppImage it resolves to the
        // temporary FUSE mount point (/tmp/.mount_AuraLaXXXXX/usr/bin/AuraLauncher)
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

/**
 * OS detection utilities.
 */
object OS {
    private val osName = System.getProperty("os.name").lowercase()

    val isWindows: Boolean = osName.contains("windows")
    val isMacOS: Boolean = osName.contains("mac") || osName.contains("darwin")
    val isLinux: Boolean = osName.contains("linux") || osName.contains("unix")

    fun getName(): String = when {
        isWindows -> "Windows"
        isMacOS -> "macOS"
        isLinux -> "Linux"
        else -> "Unknown"
    }
}
