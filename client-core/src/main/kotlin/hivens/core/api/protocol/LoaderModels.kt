package hivens.core.api.protocol

import hivens.core.api.dto.SmartyNews
import hivens.core.api.dto.SmartyServer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Request body for `action=loader`. Unsigned, no auth required.
 *
 * Note the misspelled `cheksum` field -- preserved as-is because that's
 * what the upstream PHP backend expects (typo originated upstream, we mirror it).
 */
@Serializable
data class LoaderRequest(
    val version: String,
    val cheksum: String,
    val format: String = "jar",
    val testModeKey: String = "",
    val debug: String = "false",
)

/**
 * Response from `action=loader`.
 *
 * Empirically observed shape: `{"status":"OK","servers":[...],"news":[...]?,"testMode":bool?}`.
 * `news` is sometimes absent depending on the upstream's deployment state.
 *
 * On status [ProtocolStatus.UPDATE] the [servers] list is empty; consumer must
 * refresh the launcher hash via [hivens.core.api.protocol.LauncherHashCache]
 * and retry.
 */
@Serializable
data class LoaderResponse(
    val status: String,
    val servers: List<SmartyServer> = emptyList(),
    val news: List<SmartyNews> = emptyList(),
    @SerialName("testMode")
    val testMode: Boolean = false,
    val message: String? = null,
) {
    val parsedStatus: ProtocolStatus get() = ProtocolStatus.fromWire(status)
}
