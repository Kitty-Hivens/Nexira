package hivens.update

import hivens.core.api.interfaces.IUpdateApplicator
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * macOS update flow.
 *
 * Mounts the downloaded `.dmg` at `/Volumes/NexiraUpdate`, kills any
 * Nexira survivors, replaces the existing `.app` bundle, unmounts
 * and relaunches via `open`. Self-deletes the bash trampoline script.
 */
class MacUpdateApplicator : IUpdateApplicator {
    private val logger = LoggerFactory.getLogger(MacUpdateApplicator::class.java)

    override fun scheduleUpdate(installerPath: Path) {
        try {
            val scriptPath = Files.createTempFile("aura_update", ".sh")
            val currentBinary = resolveExecutable()
            val currentAppBundle = currentBinary.parent?.parent?.parent ?: Paths.get("/Applications/Nexira.app")
            val targetDir = currentAppBundle.parent ?: Paths.get("/Applications")

            // Paths are passed via env vars, not string-interpolated into the
            // script body, so any character a Path may legitimately contain
            // (spaces, quotes, dollars) cannot escape the bash quoting and
            // execute as a command. The "$VAR" form inside double quotes
            // still expands the variable but treats its content as literal.
            scriptPath.toFile().writeText(
                $$"""
                #!/bin/bash
                set -e

                # Wait for launcher to exit
                sleep 2

                # Kill any remaining processes
                killall -9 Nexira 2>/dev/null || true

                # Mount DMG
                echo "Mounting update image..."
                hdiutil attach "$INSTALLER" -mountpoint /Volumes/NexiraUpdate -nobrowse -quiet || exit 1

                if [ ! -d "/Volumes/NexiraUpdate/Nexira.app" ]; then
                    echo "Error: Nexira.app not found in DMG"
                    hdiutil detach /Volumes/NexiraUpdate -quiet || true
                    exit 1
                fi

                # Remove old version
                echo "Removing old version..."
                rm -rf "$BUNDLE" || true

                # Copy new version
                echo "Installing new version..."
                cp -R /Volumes/NexiraUpdate/Nexira.app "$TARGET/"

                # Unmount DMG
                echo "Cleaning up..."
                hdiutil detach /Volumes/NexiraUpdate -quiet
                rm -f "$INSTALLER"

                # Relaunch
                echo "Launching updated version..."
                sleep 1
                open "$TARGET/Nexira.app"

                # Cleanup script
                rm -f "$SCRIPT"
            """.trimIndent())

            scriptPath.toFile().setExecutable(true)
            logger.info("Scheduled macOS update: {} targeting {}", installerPath, targetDir)

            Runtime.getRuntime().addShutdownHook(Thread {
                try {
                    val pb = ProcessBuilder("bash", scriptPath.toString())
                    pb.environment().apply {
                        put("INSTALLER", installerPath.toString())
                        put("BUNDLE", currentAppBundle.toString())
                        put("TARGET", targetDir.toString())
                        put("SCRIPT", scriptPath.toString())
                    }
                    pb.start()
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
        // .app/Contents/app/<jar>.jar  ->  .app/Contents/MacOS/Nexira
        val classPath = System.getProperty("java.class.path")
        if (!classPath.isNullOrBlank()) {
            val jarPath = Paths.get(classPath.split(":").first()).toAbsolutePath()
            val contents = jarPath.parent?.parent
            if (contents != null) return contents.resolve("MacOS").resolve("Nexira")
        }
        error("Cannot resolve macOS launcher binary: java.class.path missing or not in expected .app/Contents/app/ layout")
    }
}
