package hivens.core.api.interfaces

import hivens.core.api.model.ServerProfile
import hivens.core.data.LauncherLogType
import hivens.core.data.SessionData
import java.io.IOException
import java.nio.file.Path

interface ILauncherService {
    /**
     * Assembles and spawns the Minecraft launch process; returns the
     * running [Process] for monitoring.
     */
    @Throws(IOException::class)
    suspend fun launchClient(
        sessionData: SessionData,
        serverProfile: ServerProfile,
        clientRootPath: Path,
        javaExecutablePath: Path,
        allocatedMemoryMB: Int,
    ): Process

    /** Same as [launchClient], plus streams stdout / stderr through [onLog]. */
    @Throws(IOException::class)
    suspend fun launchClientWithLogs(
        sessionData: SessionData,
        serverProfile: ServerProfile,
        clientRootPath: Path,
        javaExecutablePath: Path,
        allocatedMemoryMB: Int,
        onLog: (String, LauncherLogType) -> Unit,
    ): Process
}
