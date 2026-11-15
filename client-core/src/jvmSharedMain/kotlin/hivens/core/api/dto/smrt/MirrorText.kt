package hivens.core.api.dto.smrt

/**
 * The mirror's text in the reader's language [tag], else the untagged copy.
 *
 * The mirror settles its maps before they ship. A translation left blank is
 * absent rather than empty, tags arrive trimmed and lower-cased, and the
 * untagged copy is never left empty while a map beside it holds something. So a
 * lookup by the lower-cased tag is the whole rule on this side, and a tag the
 * map does not carry, blank included, reads the untagged copy.
 */
fun inLanguage(untagged: String?, byLanguage: Map<String, String>?, tag: String): String? =
    inLanguage(untagged, byLanguage, listOf(tag))

/** The mirror's text in the first of the reader's languages [tags] it carries, else the untagged copy. */
fun inLanguage(untagged: String?, byLanguage: Map<String, String>?, tags: List<String>): String? =
    tags.firstNotNullOfOrNull { byLanguage?.get(it.trim().lowercase()) } ?: untagged
