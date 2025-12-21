package hivens.core.api

import hivens.core.data.AuthStatus

/**
 * Исключение, выбрасываемое при неудачной аутентификации на сервере.
 */
class AuthException(
    val status: AuthStatus,
    message: String
) : Exception(message)
