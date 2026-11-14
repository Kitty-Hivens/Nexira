package hivens.widget.model

/**
 * The version of an API a module publishes for other modules to compile against,
 * as a major and a minor number.
 *
 * A minor step only adds: a key, a field with a default, a value of a choice that a
 * reader treats as unknown. A module built against an older minor of the same major
 * therefore still finds everything it uses, and loads. A major step removes or
 * changes something, so a module built against another major is refused, and so is
 * one built against a newer minor than the one present, which may use what this one
 * does not have.
 *
 * Kept apart from the widget API's own number, which says whether a compiled widget
 * can link against the Compose runtime at all and stays one exact number.
 */
data class ApiVersion(val major: Int, val minor: Int) : Comparable<ApiVersion> {
    init {
        require(major >= 0 && minor >= 0) { "an API version has no negative part: $major.$minor" }
    }

    /** Whether a module built against [builtAgainst] can run against this version. */
    fun serves(builtAgainst: ApiVersion): Boolean = major == builtAgainst.major && builtAgainst.minor <= minor

    override fun compareTo(other: ApiVersion): Int = compareValuesBy(this, other, ApiVersion::major, ApiVersion::minor)

    /** The form [parse] reads back: `major.minor`. */
    override fun toString(): String = "$major.$minor"

    companion object {
        /** [text] as `major.minor`, surrounding space forgiven, or null when it is not that. */
        fun parse(text: String): ApiVersion? {
            val parts = text.trim().split('.')
            if (parts.size != 2) return null
            val major = parts[0].toIntOrNull() ?: return null
            val minor = parts[1].toIntOrNull() ?: return null
            if (major < 0 || minor < 0) return null
            return ApiVersion(major, minor)
        }

        /**
         * The APIs a module manifest names, written as `id=major.minor` entries
         * separated by commas, or null when an entry is not that. An empty or blank
         * text names none. An id named twice is an error too, since the two
         * versions could disagree.
         */
        fun parseAll(text: String): Map<String, ApiVersion>? {
            if (text.isBlank()) return emptyMap()
            val out = LinkedHashMap<String, ApiVersion>()
            text.split(',').forEach { entry ->
                val id = entry.substringBefore('=', missingDelimiterValue = "").trim()
                if (id.isEmpty()) return null
                val version = parse(entry.substringAfter('=')) ?: return null
                if (out.put(id, version) != null) return null
            }
            return out
        }
    }
}
