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
    byLanguage?.get(tag.trim().lowercase()) ?: untagged
