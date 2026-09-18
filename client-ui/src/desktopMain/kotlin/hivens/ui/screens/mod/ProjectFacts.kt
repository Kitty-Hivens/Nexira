package hivens.ui.screens.mod

import androidx.compose.ui.graphics.Color
import hivens.core.api.dto.modrinth.ModrinthDisclosure
import hivens.ui.i18n.AppStrings

/**
 * The rules that turn what a catalogue stores into what a reader reads.
 *
 * Kept out of the composables on purpose. Every one of them was worked out
 * against real payloads in the mock-up and every one is a judgement that can be
 * wrong in a way a screenshot shows and a type does not: fifty-eight game
 * versions folded into four chips, a loader's own capitalisation, two
 * independent side flags read as one phrase. Pure functions over [AppStrings], so
 * the judgement is testable without a composition and says the same thing in
 * four languages.
 */

/**
 * How a loader writes its own name.
 *
 * Capitalising the first letter gives "Neoforge", which is not a thing. These are
 * proper nouns with their own casing and the only correct source is the project
 * itself, so they are a table rather than a rule. Anything not in it falls back
 * to the rule, which is right more often than it is wrong for a name nobody here
 * has seen yet.
 */
fun loaderLabel(loader: String): String = when (loader.lowercase()) {
    "fabric" -> "Fabric"
    "forge" -> "Forge"
    "neoforge" -> "NeoForge"
    "quilt" -> "Quilt"
    "legacy-fabric" -> "Legacy Fabric"
    "optifine" -> "OptiFine"
    "iris" -> "Iris"
    "canvas" -> "Canvas"
    "datapack" -> "Datapack"
    "minecraft" -> "Minecraft"
    "vanilla" -> "Vanilla"
    else -> loader.replaceFirstChar(Char::uppercase)
}

/**
 * Placeholder brand colours, and marked as such.
 *
 * The loader logos are not ours to ship, so the chip carries the name and a
 * colour instead, which is all a reader needs to pick their loader out of a row.
 * These values are eyeballed and want replacing with each project's real brand
 * colour, which is a licensing question rather than a drawing one.
 */
fun loaderDot(loader: String): Color = when (loader.lowercase()) {
    "fabric" -> Color(0xFFDBD0B4)
    "forge" -> Color(0xFF6A8FD8)
    "neoforge" -> Color(0xFFF16436)
    "quilt" -> Color(0xFF9B6CE0)
    "iris" -> Color(0xFF6BC1E8)
    "optifine" -> Color(0xFFC0392B)
    else -> Color(0xFF8A8A96)
}

/** One place a project runs, as its own chip. */
enum class Environment { Client, Server, Both }

/**
 * Where a project runs, as chips rather than as one sentence.
 *
 * The first pass here collapsed the two side flags into a single phrase, which
 * loses the case the fields exist for: a mod that is required on the client and
 * optional on the server is BOTH "client-side" and "works on a server", and one
 * word has to pick. The catalogue's own page emits one chip per true statement and
 * so does this.
 *
 * Empty where neither side was answered, which is a file nobody asked, not a file
 * that runs nowhere.
 */
fun environments(client: String?, server: String?): List<Environment> {
    if (client == null || server == null) return emptyList()
    if (client == UNKNOWN && server == UNKNOWN) return emptyList()
    if (client == UNSUPPORTED && server == UNSUPPORTED) return emptyList()

    val out = mutableListOf<Environment>()
    if ((client == REQUIRED && server != REQUIRED) || (client == OPTIONAL && server == OPTIONAL)) {
        out += Environment.Client
    }
    if ((server == REQUIRED && client != REQUIRED) || (client == OPTIONAL && server == OPTIONAL)) {
        out += Environment.Server
    }
    if (client != UNSUPPORTED && server != UNSUPPORTED && client != UNKNOWN && server != UNKNOWN) {
        out += Environment.Both
    }
    return out
}

fun environmentLabel(environment: Environment, s: AppStrings): String = when (environment) {
    Environment.Client -> s.modEnvClientOnly
    Environment.Server -> s.modEnvServerOnly
    Environment.Both -> s.modEnvBoth
}

private const val REQUIRED = "required"
private const val OPTIONAL = "optional"
private const val UNSUPPORTED = "unsupported"
private const val UNKNOWN = "unknown"

/** Short, human, and never the raw LicenseRef machinery. */
fun licenseLabel(id: String?, name: String?, s: AppStrings): String {
    val chosen = id?.takeIf { it.isNotBlank() } ?: return name?.takeIf { it.isNotBlank() } ?: s.modLicenseUnknown
    return when {
        chosen == "LicenseRef-All-Rights-Reserved" -> s.modLicenseAllRights
        chosen.startsWith("LicenseRef-") -> chosen.removePrefix("LicenseRef-").replace('-', ' ')
        else -> chosen
    }
}

/**
 * A download count a 300dp column can hold.
 *
 * The decimal is formatted here and the unit comes from the locale, because
 * "1.2 M" and "1,2 Mio." differ in both halves and only one of them is a number.
 */
fun compactCount(n: Long, s: AppStrings): String = when {
    n >= 1_000_000 -> s.compactMillions("%.1f".format(n / 1_000_000.0))
    n >= 1_000 -> s.compactThousands("%.1f".format(n / 1_000.0))
    else -> n.toString()
}

/**
 * One declaration as a headline and the author's own lines under it.
 *
 * The consent mode is the whole of what a reader wants from a telemetry entry, so
 * it never collapses into the bare word. A type this build has never heard of
 * comes through as itself: the vocabulary is the server's and it grows, and
 * hiding the newest thing an author can disclose is the one failure this block
 * exists to prevent.
 */
fun disclosureLine(d: ModrinthDisclosure, s: AppStrings): Pair<String, List<String>> = when (d.type) {
    ModrinthDisclosure.TELEMETRY -> when (d.consent) {
        "opt_in" -> s.modDisclosureTelemetryOptIn
        "opt_out" -> s.modDisclosureTelemetryOptOut
        "always_active" -> s.modDisclosureTelemetryAlways
        else -> s.modDisclosureTelemetry
    } to d.dataCollected
    ModrinthDisclosure.ADVERTISEMENTS -> s.modDisclosureAds to listOfNotNull(d.note)
    ModrinthDisclosure.PAID_FEATURES -> s.modDisclosurePaid to d.features
    ModrinthDisclosure.AI_CONTENT -> s.modDisclosureAiContent to listOfNotNull(d.note)
    ModrinthDisclosure.AI_FUNCTIONALITY -> s.modDisclosureAiFunctionality to listOfNotNull(d.note)
    ModrinthDisclosure.SYSTEM_INTERACTIONS -> s.modDisclosureSystem to listOfNotNull(d.note)
    ModrinthDisclosure.EPILEPSY_TRIGGERS -> s.modDisclosureEpilepsy to listOfNotNull(d.note)
    ModrinthDisclosure.DERIVATIVE_WORK ->
        d.type to (listOfNotNull(d.note) + d.sources.map { it.label }.filter { it.isNotBlank() })
    else -> d.type to listOfNotNull(d.note)
}

/** The one declaration that can hurt a reader rather than inform them. */
fun disclosureIsWarning(d: ModrinthDisclosure): Boolean =
    d.type == ModrinthDisclosure.EPILEPSY_TRIGGERS

/**
 * The order declarations are read in, which is not the order they arrive in.
 *
 * The API returns them however the author edited them, so the same two projects
 * put "contains advertising" in different places and a reader scanning a column
 * has to read all of it every time. The one that can physically hurt someone goes
 * first; the rest run from what the project IS down to what it does behind you.
 * Anything this build has never heard of keeps its place at the end rather than
 * being dropped.
 */
fun disclosuresInReadingOrder(disclosures: List<ModrinthDisclosure>): List<ModrinthDisclosure> =
    disclosures.sortedBy { d ->
        DISCLOSURE_ORDER.indexOf(d.type).takeIf { it >= 0 } ?: DISCLOSURE_ORDER.size
    }

private val DISCLOSURE_ORDER = listOf(
    ModrinthDisclosure.EPILEPSY_TRIGGERS,
    ModrinthDisclosure.AI_CONTENT,
    ModrinthDisclosure.AI_FUNCTIONALITY,
    ModrinthDisclosure.ADVERTISEMENTS,
    ModrinthDisclosure.PAID_FEATURES,
    ModrinthDisclosure.TELEMETRY,
    ModrinthDisclosure.SYSTEM_INTERACTIONS,
    ModrinthDisclosure.DERIVATIVE_WORK,
)

/**
 * What one of the author's addresses is called.
 *
 * The destination's own name wins where it has one, which is how a row of
 * donation links stops being five identical lines.
 */
fun linkLabel(link: ProjectLink, s: AppStrings): String =
    link.label ?: linkLabel(link.kind, s)

private fun linkLabel(kind: ProjectLinkKind, s: AppStrings): String = when (kind) {
    ProjectLinkKind.Issues -> s.modLinkIssues
    ProjectLinkKind.Source -> s.modLinkSource
    ProjectLinkKind.Wiki -> s.modLinkWiki
    ProjectLinkKind.Discord -> s.modLinkDiscord
    ProjectLinkKind.Donate -> s.modLinkDonate
}
