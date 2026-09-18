package hivens.core.api.dto.modrinth

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What an author declares their project does, from `GET /v3/project/{id}/disclosures`.
 *
 * This is the one thing on a Modrinth project page that nobody else surfaces,
 * and it is the reason the page is worth rendering natively rather than linking
 * out: it is where a mod says, in the author's own words, that it phones home,
 * that it shows advertising, that parts of it are paid, or that it touches the
 * system outside the game. A launcher that is about not being spied on should
 * put that in front of someone BEFORE they install, not behind a browser.
 *
 * Reported, never judged. The type is a fact and the note is the author's own
 * sentence; the only entry that earns a colour is [EPILEPSY_TRIGGERS], because
 * that one can hurt a reader rather than merely inform them.
 */
@Serializable
data class ModrinthDisclosure(
    /**
     * `telemetry` / `advertisements` / `paid_features` / `ai_content` /
     * `ai_functionality` / `system_interactions` / `epilepsy_triggers` /
     * `derivative_work`. Kept as a string: the vocabulary is the server's and it
     * grows, and an unknown entry must still render as itself rather than vanish
     * into an enum's else branch.
     */
    val type: String,
    /** The author's own note, when they wrote one. */
    val note: String? = null,
    /**
     * For [TELEMETRY] only: `opt_in` / `opt_out` / `always_active`. The
     * difference between the three is the whole of what a reader wants to know,
     * so it is never flattened into the word "telemetry".
     */
    val consent: String? = null,
    /** For `paid_features`: what is behind the payment, one entry per line. */
    val features: List<String> = emptyList(),
    /** For `telemetry`: what leaves the machine, or a link to the policy that says. */
    @SerialName("data_collected") val dataCollected: List<String> = emptyList(),
    /** Sources a derivative work is derived from. */
    val sources: List<ModrinthDisclosureSource> = emptyList(),
) {
    companion object {
        const val TELEMETRY = "telemetry"
        const val ADVERTISEMENTS = "advertisements"
        const val PAID_FEATURES = "paid_features"
        const val AI_CONTENT = "ai_content"
        const val AI_FUNCTIONALITY = "ai_functionality"
        const val SYSTEM_INTERACTIONS = "system_interactions"
        const val EPILEPSY_TRIGGERS = "epilepsy_triggers"
        const val DERIVATIVE_WORK = "derivative_work"
    }
}

/** One thing a derivative work is derived from. */
@Serializable
data class ModrinthDisclosureSource(
    val label: String = "",
    val link: String? = null,
    val note: String? = null,
)

/** The envelope `/v3/project/{id}/disclosures` answers with. */
@Serializable
data class ModrinthDisclosures(
    val disclosures: List<ModrinthDisclosure> = emptyList(),
)
