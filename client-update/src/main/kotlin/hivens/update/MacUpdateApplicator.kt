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
 * See [INSTALL_SCRIPT] for the order the bundle is replaced in.
 */
class MacUpdateApplicator : IUpdateApplicator {
    private val logger = LoggerFactory.getLogger(MacUpdateApplicator::class.java)
    private val scheduled = ScheduledInstall()

    override fun scheduleUpdate(installerPath: Path) {
        try {
            val scriptPath = Files.createTempFile("aura_update", ".sh")
            val currentBinary = resolveExecutable()
            val currentAppBundle = currentBinary.parent?.parent?.parent ?: Paths.get("/Applications/Nexira.app")

            scriptPath.toFile().writeText(INSTALL_SCRIPT)
            scriptPath.toFile().setExecutable(true)
            logger.info("Scheduled macOS update: {} replacing {}", installerPath, currentAppBundle)

            scheduled.replace("launcher-update-install") {
                try {
                    val pb = ProcessBuilder("bash", scriptPath.toString())
                    pb.environment().apply {
                        put("INSTALLER", installerPath.toString())
                        put("BUNDLE", currentAppBundle.toString())
                        put("SCRIPT", scriptPath.toString())
                        put("MOUNT", MOUNT_POINT)
                    }
                    pb.start()
                } catch (e: Exception) {
                    logger.error("Failed to execute update script", e)
                }
            }
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

    internal companion object {
        const val MOUNT_POINT = "/Volumes/NexiraUpdate"

        /**
         * Runs after the launcher has exited, with its paths in the environment, so
         * nothing a path may contain can escape the quoting.
         *
         * The installed bundle is removed only once the new one is complete beside
         * it. Removing it first, as this did, left no launcher at all when the copy
         * failed halfway, a full disk or a logout in the middle of it, the same
         * window the Linux swap and the Java install are ordered to close. The copy
         * lands at `$BUNDLE.new`, the old bundle steps aside to `$BUNDLE.old`, and
         * the new one takes its name. A failure before that leaves the installed
         * version where it was and starts it again.
         *
         * The update goes in under the name the bundle already has, so a renamed
         * or moved install is replaced where it is rather than added beside.
         */
        val INSTALL_SCRIPT = $$"""
            #!/bin/bash
            set -e

            STAGED="$BUNDLE.new"
            OLD="$BUNDLE.old"

            # Starts whatever is installed now and stops here.
            give_up() {
                echo "Error: $1"
                rm -rf "$STAGED"
                hdiutil detach "$MOUNT" -quiet || true
                open "$BUNDLE" || true
                exit 1
            }

            # Wait for launcher to exit
            sleep 2

            # Kill any remaining processes
            killall -9 Nexira 2>/dev/null || true

            echo "Mounting update image..."
            hdiutil attach "$INSTALLER" -mountpoint "$MOUNT" -nobrowse -quiet || give_up "could not mount the update image"
            [ -d "$MOUNT/Nexira.app" ] || give_up "Nexira.app not found in the update image"

            echo "Copying new version..."
            rm -rf "$STAGED" "$OLD"
            cp -R "$MOUNT/Nexira.app" "$STAGED" || give_up "could not copy the new version"
            hdiutil detach "$MOUNT" -quiet || true

            echo "Installing new version..."
            if [ -e "$BUNDLE" ]; then mv "$BUNDLE" "$OLD" || give_up "could not move the installed version aside"; fi
            if ! mv "$STAGED" "$BUNDLE"; then
                [ -e "$OLD" ] && mv "$OLD" "$BUNDLE"
                give_up "could not put the new version in place"
            fi
            rm -rf "$OLD"
            rm -f "$INSTALLER"

            echo "Launching updated version..."
            sleep 1
            open "$BUNDLE"

            rm -f "$SCRIPT"
        """.trimIndent()
    }
}
