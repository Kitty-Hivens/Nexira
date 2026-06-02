package hivens.ui.puppet

import kotlinx.serialization.Serializable

/**
 * Wire format for the puppet HTTP API. Flat shape (no polymorphic
 * sealed hierarchy) so curl clients can pluck fields with `jq` without
 * needing to know Kotlin serialization quirks like class discriminators.
 *
 * [kind] is the discriminator: "click" | "field" | "toggle". Depending
 * on [kind], either [value] (field) or [boolValue] (toggle) carries the
 * current widget state; for "click" both are null.
 */
@Serializable
internal data class PuppetElement(
    val id: String,
    val kind: String,
    val value: String? = null,
    val boolValue: Boolean? = null,
    val enabled: Boolean = true,
)

@Serializable
internal data class PuppetSnapshot(
    val screen: String,
    val elements: List<PuppetElement>,
)

@Serializable
internal data class ScreenResponse(val screen: String) // TODO: Class "ScreenResponse" is never used

@Serializable
internal data class PuppetOk(val ok: Boolean = true) // TODO: Class "PuppetOk" is never used

@Serializable
internal data class PuppetError(val error: String) // TODO: Class "PuppetError" is never used

@Serializable
internal data class ClickRequest(val id: String) // TODO: Class "ClickRequest" is never used

@Serializable
internal data class SetFieldRequest(val id: String, val value: String) // TODO: Class "SetFieldRequest" is never used

@Serializable
internal data class SetToggleRequest(val id: String, val value: Boolean) // TODO: Class "SetToggleRequest" is never used
