package hivens.launcher.modrinth

/**
 * What a catalogue filter narrows. Each one is a field of the catalogue's search
 * index, and how several chosen values of it combine is the field's own rule, see
 * [searchExpression].
 */
enum class FilterField {
    /** A game version. Several are any of them. */
    GameVersion,

    /** A loader a build is published for. Several are any of them. */
    Loader,

    /** A category. Several are all of them, the way the catalogue's own sidebar reads. */
    Category,

    /** A resource pack's resolution. Several are any of them: a pack has one. */
    Resolution,

    /** Where the project runs, [ENV_CLIENT] or [ENV_SERVER]. */
    Environment,

    /** Whether the licence is an open one. The value is ignored. */
    OpenSource,

    /** Something the author declares about the project, like telemetry. Only ever excluded. */
    Disclosure,

    /** A project by id. Only ever excluded, which is how what a pack already has is hidden. */
    Project,
}

/** One chosen value. [excluded] keeps it out rather than asking for it. */
data class SearchFilter(val field: FilterField, val value: String, val excluded: Boolean = false)

const val ENV_CLIENT = "client"
const val ENV_SERVER = "server"

/**
 * The catalogue's filter expression for [projectType] narrowed by [filters], in its
 * own syntax: the `new_filters` parameter, which the v2 search takes beside the
 * legacy facets and which is the only one of the two that can say "not".
 *
 * The rules are the catalogue's own sidebar's, so a set of choices finds here what
 * it finds there. Values are quoted, because a bare `1.20` is read as a number and
 * stops matching the version string.
 *
 * Exclusion works per build, not per project: the index holds one document per
 * build, so leaving out `fabric` removes the Fabric builds and keeps a project that
 * also publishes for NeoForge. That is the catalogue's behaviour too.
 */
fun searchExpression(projectType: String, filters: Collection<SearchFilter>): String {
    val parts = mutableListOf<String>()
    val anyOf = linkedMapOf<String, MutableList<String>>()
    val noneOf = linkedMapOf<String, MutableList<String>>()

    for (filter in filters.distinct().sortedWith(compareBy({ it.field.ordinal }, { it.value }))) {
        val field = indexFieldOf(filter.field)
        val value = if (filter.field == FilterField.OpenSource) "true" else filter.value
        when {
            filter.field == FilterField.Environment -> Unit
            filter.excluded -> noneOf.getOrPut(field) { mutableListOf() } += value
            filter.field == FilterField.Disclosure || filter.field == FilterField.Project -> Unit
            filter.field == FilterField.Category || filter.field == FilterField.OpenSource ->
                parts += "$field = ${quote(value)}"
            else -> anyOf.getOrPut(field) { mutableListOf() } += value
        }
    }
    anyOf.forEach { (field, values) ->
        parts += if (values.size == 1) "$field = ${quote(values[0])}" else "$field IN [${values.joinToString(", ") { quote(it) }}]"
    }
    noneOf.forEach { (field, values) ->
        parts += "$field NOT IN [${values.joinToString(", ") { quote(it) }}]"
    }
    environmentValues(
        client = filters.any { it.field == FilterField.Environment && it.value == ENV_CLIENT && !it.excluded },
        server = filters.any { it.field == FilterField.Environment && it.value == ENV_SERVER && !it.excluded },
    )?.let { values -> parts += "environment IN [${values.joinToString(", ") { quote(it) }}]" }
    parts += "project_types = ${quote(projectType)}"
    return parts.joinToString(" AND ")
}

private fun indexFieldOf(field: FilterField): String = when (field) {
    FilterField.GameVersion -> "game_versions"
    FilterField.Loader, FilterField.Category, FilterField.Resolution -> "categories"
    FilterField.Environment -> "environment"
    FilterField.OpenSource -> "open_source"
    FilterField.Disclosure -> "disclosure_types"
    FilterField.Project -> "project_id"
}

/**
 * The environments a project may declare and still run where it is wanted.
 *
 * A project for the client is one the client can run, whatever the server does,
 * so it is not only `client_only`. Asking for both is a project that has a part on
 * each side. The lists are the catalogue's own.
 */
private fun environmentValues(client: Boolean, server: Boolean): List<String>? = when {
    client && server -> listOf(
        "client_only_server_optional",
        "server_only_client_optional",
        "client_and_server",
        "client_or_server",
        "client_or_server_prefers_both",
    )
    client -> listOf("client_only", "client_only_server_optional", "client_or_server_prefers_both", "client_or_server")
    server -> listOf(
        "server_only",
        "dedicated_server_only",
        "server_only_client_optional",
        "client_or_server_prefers_both",
        "client_or_server",
    )
    else -> null
}

private fun quote(value: String): String =
    if (value == "true" || value == "false") value else "`${value.replace("`", "")}`"
