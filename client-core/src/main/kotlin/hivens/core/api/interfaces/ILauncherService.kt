package hivens.core.api.interfaces

import hivens.core.api.model.ServerProfile
import hivens.core.data.LauncherLogType
import hivens.core.data.SessionData
import java.io.IOException
import java.nio.file.Path

/**
 * Contract for the Minecraft client launch service.
 */
interface ILauncherService {

    /**
     * Collects and executes the Minecraft client launch command.
     *
     * @param sessionData Session data (accessToken, uuid, playerName).
     * @param serverProfile Data about the selected server (version, name).
     * @param clientRootPath The absolute path to the client root.
     * @param javaExecutablePath The absolute path to the java executable.
     * @param allocatedMemoryMB The amount of allocated memory in MB (e.g., 4096).
     * @return The running process (Process) to monitor.
     * @throws IOException if there is an I/O error at startup.
     */
    @Throws(IOException::class)
    suspend fun launchClient(
        sessionData: SessionData,
        serverProfile: ServerProfile,
        clientRootPath: Path,
        javaExecutablePath: Path,
        allocatedMemoryMB: Int
    ): Process

    @Throws(IOException::class)
    suspend fun launchClientWithLogs(
        sessionData: SessionData,
        serverProfile: ServerProfile,
        clientRootPath: Path,
        javaExecutablePath: Path,
        allocatedMemoryMB: Int,
        onLog: (String, LauncherLogType) -> Unit
    ): Process
}
