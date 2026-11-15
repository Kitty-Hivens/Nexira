package hivens.i18n

import java.io.File

/**
 * What a build says about one namespace's shipped tables: keys a language lacks, keys the code
 * asks for that English does not have, and messages that do not parse. All of it is a warning,
 * since a missing translation shows its key at runtime and is never a reason to stop a build.
 *
 * Called by the build through its class path, so it takes and answers plain JDK types.
 */
object TableCheck {

    /** A key looked up with a literal: `.text("key"` with or without arguments after it. */
    private val LOOKUP = Regex("""\.text\(\s*"([^"\\$]+)"""")

    /**
     * Warnings for the tables in [tablesDir], one `<tag>.properties` per language, English being
     * [english], against the lookups in [sources]. Each warning is one line.
     */
    @JvmStatic
    fun check(tablesDir: File, english: String, sources: List<File>): List<String> {
        val tables = tablesDir.listFiles { file -> file.isFile && file.name.endsWith(".properties") }
            .orEmpty()
            .sortedBy { it.name }
            .associate { it.name.removeSuffix(".properties") to it.inputStream().use(PropertiesFiles::read) }
        val warnings = ArrayList<String>()
        val base = tables[english] ?: return listOf("no $english.properties in $tablesDir, so nothing can be checked against English")

        tables.forEach { (tag, table) ->
            table.toSortedMap().forEach { (key, text) ->
                Message.faultIn(text)?.let { warnings += "$tag: '$key' is not a valid message ($it)" }
            }
            if (tag == english) return@forEach
            val missing = (base.keys - table.keys).sorted()
            if (missing.isNotEmpty()) {
                warnings += "$tag: ${missing.size} key(s) English has are not translated and read in English: ${missing.joinToString(", ")}"
            }
        }

        sources.sortedBy { it.path }.forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                LOOKUP.findAll(line).map { it.groupValues[1] }.filter { it !in base }.forEach { key ->
                    warnings += "${file.path}:${index + 1}: '$key' is looked up but English has no such key, so it shows as written"
                }
            }
        }
        return warnings
    }
}
