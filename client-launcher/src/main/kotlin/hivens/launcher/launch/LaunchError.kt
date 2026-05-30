package hivens.launcher.launch

/**
 * Semantic reasons a launch can terminate with [LaunchState.Error].
 *
 * The UI maps each case to an `AppStrings` key at render time. Backend
 * code stays free of localization concerns and string formatting.
 */
sealed class LaunchError {
    /** Game process exited with a non-zero status. */
    data class ExitCode(val code: Int) : LaunchError()

    /** Catch-all: an unexpected throwable; the message is included for the user. */
    data class Internal(val message: String) : LaunchError()

    /** Offline mode launched without an installed client directory. */
    data object OfflineNoClient : LaunchError()

    /** Offline mode launched without a cached manifest from a prior online run. */
    data object OfflineNoManifest : LaunchError()

    /**
     * 2FA account with an expired (or absent) cached manifest --
     * re-login through the form is required.
     */
    data object TwoFactorExpired : LaunchError()

    /** Auth call failed for a non-2FA reason (network, server reject, etc.). */
    data class AuthFail(val cause: String?) : LaunchError()

    /**
     * Pack declares an auth requirement the user has no live session
     * for. The UI renders a "sign in with <provider> to play this
     * pack" hint -- distinct from [AuthFail] (where the user IS
     * signed in but the call failed) and [TwoFactorExpired] (where
     * the cached session is unusable). [providerKey] is a stable
     * identifier the UI maps to a localized provider name.
     */
    data class MissingAuthProvider(val providerKey: String) : LaunchError()
}
