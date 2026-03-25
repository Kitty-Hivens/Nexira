package hivens.core.api

import hivens.core.data.AuthStatus

/**
 * The exception thrown when authentication to the server fails.
 */
class AuthException(
    val status: AuthStatus,
    message: String,
    /** True when failure is caused by an expired/invalid SSL certificate. */
    val isSslError: Boolean = false
) : Exception(message)
