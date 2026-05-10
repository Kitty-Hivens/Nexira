package hivens.launcher.update

import hivens.core.api.interfaces.IUpdateApplicator
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * macOS update flow.
 *
 * Mounts the downloaded `.dmg` at `/Volumes/AuraUpdate`, kills any
 * AuraLauncher survivors, replaces the existing `.app` bundle, unmounts
 * and relaunches via `open`. Self-deletes the bash trampoline script.
 */
class MacUpdateApplicator : IUpdateApplicator {
    private val logger = LoggerFactory.getLogger(MacUpdateApplicator::class.java)

    override fun scheduleUpdate(installerPath: Path) {
        try {
            val scriptPath = Files.createTempFile("aura_update", ".sh")
            val currentBinary = resolveExecutable()
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
                hdiutil attach "$installerPath" -mountpoint /Volumes/AuraUpdate -nobrowse -quiet || exit 1

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
                rm -f "$installerPath"

                # Relaunch
                echo "Launching updated version..."
                sleep 1
                open "$targetDir/AuraLauncher.app"

                # Cleanup script
                rm -f "$scriptPath"
            """.trimIndent())

            scriptPath.toFile().setExecutable(true)
            logger.info("Scheduled macOS update: {} targeting {}", installerPath, targetDir)

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

    private fun resolveExecutable(): Path {
        // .app/Contents/app/<jar>.jar  ->  .app/Contents/MacOS/AuraLauncher
        val classPath = System.getProperty("java.class.path")
        if (!classPath.isNullOrBlank()) {
            val jarPath = Paths.get(classPath.split(":").first()).toAbsolutePath()
            val contents = jarPath.parent?.parent
            if (contents != null) return contents.resolve("MacOS").resolve("AuraLauncher")
        }
        error("Cannot resolve macOS launcher binary: java.class.path missing or not in expected .app/Contents/app/ layout")
    }
}
