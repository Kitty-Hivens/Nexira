package hivens.ui.i18n

/**
 * Picks the [one]/[few]/[many] form by the East-Slavic rule (ru). The caller
 * interpolates the number and passes the agreeing forms; this only selects.
 * The 11-14 carve-out is the non-obvious part: they take [many] despite ending
 * in 1-4 (so 1 файл, but 11 файлов; 2 файла, but 12 файлов).
 */
internal fun russianPlural(n: Int, one: String, few: String, many: String): String {
    val mod10 = n % 10
    val mod100 = n % 100
    return when {
        mod10 == 1 && mod100 != 11 -> one
        mod10 in 2..4 && mod100 !in 12..14 -> few
        else -> many
    }
}

/** One-vs-other selector for languages with a single plural break (en, de). */
internal fun twoFormPlural(n: Int, one: String, other: String): String =
    if (n == 1) one else other

/**
 * Casing/brand fallback for a Modrinth category id with no localized label:
 * loader ids keep their brand casing, everything else is split on -/_ and
 * title-cased ("kitchen-sink" -> "Kitchen Sink"). The per-locale
 * [AppStrings.modrinthCategory] impls translate the modpack taxonomy and
 * delegate brands + unknowns here.
 */
internal fun humanizeCategory(raw: String): String = when (raw) {
    "fabric"   -> "Fabric"
    "forge"    -> "Forge"
    "quilt"    -> "Quilt"
    "neoforge" -> "NeoForge"
    else -> raw.split('-', '_', ' ')
        .filter { it.isNotBlank() }
        .joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }
}
