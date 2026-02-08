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

    private fun scheduleWindowsUpdate(msiPath: Path) {
        try {
            val psScript = Files.createTempFile("aura_update", ".ps1")
            val launcherPath = getCurrentExecutable()
            
            psScript.toFile().writeText("""
                # Wait for launcher to exit
                Start-Sleep -Seconds 2
                
                # Ensure all processes are closed
                Get-Process -Name "AuraLauncher" -ErrorAction SilentlyContinue | Stop-Process -Force
                
                # Run MSI installer
                Write-Host "Installing update..."
                Start-Process -FilePath "msiexec.exe" -ArgumentList "/i", "`"$msiPath`"", "/quiet", "/norestart" -Wait
                
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
                Remove-Item "$msiPath" -Force -ErrorAction SilentlyContinue
            """.trimIndent())

            logger.info("Scheduled Windows update: $msiPath")

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
                rm -rf /Applications/AuraLauncher.app || true
                
                # Copy new version
                echo "Installing new version..."
                cp -R /Volumes/AuraUpdate/AuraLauncher.app /Applications/
                
                # Unmount DMG
                echo "Cleaning up..."
                hdiutil detach /Volumes/AuraUpdate -quiet
                rm -f "$dmgPath"
                
                # Relaunch
                echo "Launching updated version..."
                sleep 1
                open /Applications/AuraLauncher.app
                
                # Cleanup script
                rm -f "$scriptPath"
            """.trimIndent())

            scriptPath.toFile().setExecutable(true)
            logger.info("Scheduled macOS update: $dmgPath")

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
            val backupPath = Paths.get("$currentExe.backup")

            logger.info("Scheduled Linux update: $currentExe -> $appImagePath")

            Runtime.getRuntime().addShutdownHook(Thread {
                try {
                    logger.info("Applying Linux update...")

                    // Backup current version
                    if (Files.exists(currentExe)) {
                        Files.move(currentExe, backupPath, StandardCopyOption.REPLACE_EXISTING)
                        logger.info("Backed up current version")
                    }

                    // Copy new version
                    Files.copy(appImagePath, currentExe, StandardCopyOption.REPLACE_EXISTING)
                    logger.info("Copied new version")

                    // Set executable permissions
                    try {
                        Files.setPosixFilePermissions(currentExe, setOf(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OWNER_EXECUTE,
                            PosixFilePermission.GROUP_READ,
                            PosixFilePermission.GROUP_EXECUTE,
                            PosixFilePermission.OTHERS_READ,
                            PosixFilePermission.OTHERS_EXECUTE
                        ))
                    } catch (_: UnsupportedOperationException) {
                        currentExe.toFile().setExecutable(true)
                    }
                    logger.info("Set executable permissions")

                    // Relaunch
                    logger.info("Relaunching updated version...")
                    val process = ProcessBuilder(currentExe.toString()).start()

                    // Cleanup
                    Thread.sleep(2000)
                    if (process.isAlive) {
                        Files.deleteIfExists(backupPath)
                        Files.deleteIfExists(appImagePath)
                        logger.info("Update completed successfully")
                    } else {
                        // Rollback on failure
                        logger.error("New version failed to start, rolling back...")
                        Files.move(backupPath, currentExe, StandardCopyOption.REPLACE_EXISTING)
                        ProcessBuilder(currentExe.toString()).start()
                    }

                } catch (e: Exception) {
                    logger.error("Update failed, attempting rollback", e)
                    try {
                        if (Files.exists(backupPath)) {
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

    // ========== HELPERS ==========

    private fun getCurrentExecutable(): Path {
        return when {
            OS.isWindows -> {
                val classPath = System.getProperty("java.class.path")
                val jarPath = Paths.get(classPath.split(";").first())
                jarPath.parent?.parent?.resolve("AuraLauncher.exe")
                    ?: Paths.get("AuraLauncher.exe")
            }
            OS.isMacOS -> {
                val userDir = System.getProperty("user.dir")
                Paths.get(userDir).resolve("../MacOS/AuraLauncher")
            }
            OS.isLinux -> {
                try {
                    Paths.get("/proc/self/exe").toRealPath()
                } catch (_: Exception) {
                    val classPath = System.getProperty("java.class.path")
                    Paths.get(classPath.split(":").first())
                }
            }
            else -> Paths.get("AuraLauncher")
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
