package hivens.core.api.interfaces

import hivens.core.api.AuthException
import hivens.core.data.SessionData
import java.io.IOException

/**
 * Контракт для сервиса аутентификации.
 * Отвечает за преобразование учетных данных в сессионные данные.
 */
interface IAuthService {

    /**
     * Выполняет аутентификацию на сервере.
     *
     * @param username Логин пользователя.
     * @param password Пароль пользователя.
     * @param serverId Идентификатор выбранного сервера (как определено API).
     * @return Объект SessionData, содержащий токен, UUID и данные клиента.
     * @throws AuthException в случае ошибок аутентификации (неверный пароль, бан и т.п.).
     * @throws IOException в случае сетевых ошибок (I/O, таймауты, DNS).
     */
    @Throws(AuthException::class, IOException::class)
    fun login(username: String, password: String, serverId: String): SessionData
}
