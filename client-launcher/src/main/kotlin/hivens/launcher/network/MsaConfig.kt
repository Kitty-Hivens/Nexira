package hivens.launcher.network

import kotlinx.serialization.Serializable

/**
 * Microsoft (MSA) OAuth configuration. The launcher ships with a BLANK
 * [clientId] by default, which disables Microsoft sign-in entirely: no provider
 * is registered, no UI affordance appears, and Microsoft-routed content stays
 * advisory (the Phase A behavior). Setting a client id activates the whole path.
 *
 * The client id is the Azure AD application id of a PUBLIC client with the
 * device-code grant ("Allow public client flows") enabled. A public-client id is
 * not a secret -- it is safe to ship -- but it is environment-specific, so it
 * lives in config rather than hard-coded.
 *
 * Override paths:
 * 1. Config file `<dataDir>/msa-config.json` (this data class serialized; partial
 *    files merge with defaults).
 * 2. System property [SYSTEM_PROP_CLIENT_ID] or env [ENV_CLIENT_ID] -- overrides
 *    just the client id.
 */
@Serializable
data class MsaConfig(
    /** Azure AD public-client application id. Blank disables Microsoft sign-in. */
    val clientId: String = "",
) {
    /** True once a client id is configured -- the single gate for all MSA wiring. */
    val enabled: Boolean get() = clientId.isNotBlank()

    companion object {
        // The "consumers" authority is the personal-Microsoft-account tenant used
        // for Minecraft/Xbox sign-in. Device-code grant: the launcher shows a code
        // + URL and polls; no embedded browser or redirect URI is needed.
        const val DEVICE_CODE_URL = "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode"
        const val TOKEN_URL       = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token"
        const val XBL_AUTH_URL    = "https://user.auth.xboxlive.com/user/authenticate"
        const val XSTS_AUTH_URL   = "https://xsts.auth.xboxlive.com/xsts/authorize"
        const val MC_LOGIN_URL    = "https://api.minecraftservices.com/authentication/login_with_xbox"
        const val MC_PROFILE_URL  = "https://api.minecraftservices.com/minecraft/profile"

        /** offline_access yields the refresh token used for silent re-auth. */
        const val SCOPE = "XboxLive.signin offline_access"

        const val SYSTEM_PROP_CLIENT_ID = "nexira.msa.clientId"
        const val ENV_CLIENT_ID = "NEXIRA_MSA_CLIENT_ID"

        /** Apply the sysprop/env client-id override on top of a parsed-from-file value. */
        fun resolve(loaded: MsaConfig = MsaConfig()): MsaConfig {
            val override = System.getProperty(SYSTEM_PROP_CLIENT_ID)?.takeIf { it.isNotBlank() }
                ?: System.getenv(ENV_CLIENT_ID)?.takeIf { it.isNotBlank() }
                ?: return loaded
            return loaded.copy(clientId = override.trim())
        }
    }
}
