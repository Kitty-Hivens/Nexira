package hivens.launcher.update

import hivens.core.api.interfaces.IUpdateApplicator
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Windows update flow.
 *
 * Inno Setup installer (`.exe`, `/SILENT`) replaces the install in-place;
 * a PowerShell script wakes ~2s after we exit, kills any AuraLauncher
 * survivors, runs the silent installer, then relaunches the freshly
 * installed binary. Script + installer self-delete on success.
 */
class WindowsUpdateApplicator : IUpdateApplicator {
    private val logger = LoggerFactory.getLogger(WindowsUpdateApplicator::class.java)

    override fun scheduleUpdate(installerPath: Path) {
        try {
            val psScript = Files.createTempFile("aura_update", ".ps1")
            val launcherPath = resolveExecutable()

            // Paths are passed via environment variables, not interpolated into
            // the script body, so any character a Windows Path may legitimately
            // contain (spaces, backticks, semicolons, etc.) cannot escape the
            // PowerShell quoting and execute as a command.
            psScript.toFile().writeText(
                $$"""
                # Wait for launcher to exit
                Start-Sleep -Seconds 2

                # Ensure all processes are closed
                Get-Process -Name "AuraLauncher" -ErrorAction SilentlyContinue | Stop-Process -Force

                # Run installer silently (Inno Setup /SILENT flag)
                Write-Host "Installing update..."
                Start-Process -FilePath "$env:INSTALLER" -ArgumentList "/SILENT", "/NORESTART" -Wait

                # Wait for installation
                Start-Sleep -Seconds 3

                # Launch new version
                if (Test-Path "$env:LAUNCHER") {
                    Write-Host "Launching updated launcher..."
                    Start-Process "$env:LAUNCHER"
                } else {
                    Write-Error "Launcher executable not found at $env:LAUNCHER"
                }

                # Cleanup
                Start-Sleep -Seconds 2
                Remove-Item "$env:SCRIPT" -Force -ErrorAction SilentlyContinue
                Remove-Item "$env:INSTALLER" -Force -ErrorAction SilentlyContinue
            """.trimIndent())

            logger.info("Scheduled Windows update: {}", installerPath)

            Runtime.getRuntime().addShutdownHook(Thread {
                try {
                    val pb = ProcessBuilder(
                        "powershell.exe",
                        "-ExecutionPolicy", "Bypass",
                        "-WindowStyle", "Hidden",
                        "-File", psScript.toString()
                    )
                    pb.environment().apply {
                        put("INSTALLER", installerPath.toString())
                        put("LAUNCHER", launcherPath.toString())
                        put("SCRIPT", psScript.toString())
                    }
                    pb.start()
                } catch (e: Exception) {
                    logger.error("Failed to execute update script", e)
                }
            })
        } catch (e: Exception) {
            logger.error("Failed to schedule Windows update", e)
            throw e
        }
    }

    private fun resolveExecutable(): Path {
        val classPath = System.getProperty("java.class.path")
            ?: error("java.class.path is unset; cannot locate Windows launcher binary")
        val jarPath = Paths.get(classPath.split(";").first()).toAbsolutePath()
        val installRoot = jarPath.parent?.parent
            ?: error("Cannot resolve Windows install root from $jarPath; expected lib/<jar>.jar inside install dir")
        return installRoot.resolve("AuraLauncher.exe")
    }
}
