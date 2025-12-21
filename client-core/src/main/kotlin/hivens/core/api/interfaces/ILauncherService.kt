package hivens.core.api.interfaces

import hivens.core.api.model.ServerProfile
import hivens.core.data.SessionData
import java.io.IOException
import java.nio.file.Path

/**
 * Контракт для сервиса запуска клиента Minecraft.
 */
interface ILauncherService {

    /**
     * Собирает и выполняет команду запуска клиента Minecraft.
     *
     * @param sessionData Данные сессии (accessToken, uuid, playerName).
     * @param serverProfile Данные о выбранном сервере (версия, имя).
     * @param clientRootPath Абсолютный путь к корню клиента.
     * @param javaExecutablePath Абсолютный путь к исполняемому файлу java.
     * @param allocatedMemoryMB Объем выделяемой памяти в МБ (e.g., 4096).
     * @return Запущенный процесс (Process) для мониторинга.
     * @throws IOException в случае ошибки I/O при запуске.
     */
    @Throws(IOException::class)
    fun launchClient(
        sessionData: SessionData,
        serverProfile: ServerProfile,
        clientRootPath: Path,
        javaExecutablePath: Path,
        allocatedMemoryMB: Int
    ): Process
}
