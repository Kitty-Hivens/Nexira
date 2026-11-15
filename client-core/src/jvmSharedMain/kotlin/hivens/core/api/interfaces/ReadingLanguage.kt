package hivens.core.api.interfaces

/**
 * The languages the person reads the launcher in, by tag, best first, where something above the
 * engine decides them: the interface's language module, which answers the chosen language and the
 * languages it falls back to. The engine asks it for what it fetches in a language, the catalogue's
 * texts and the release notes, and takes the first of these the source has. Where nothing provides
 * one, a graph assembled without the interface say, the engine reads the language the settings
 * file names.
 */
fun interface ReadingLanguage {
    fun tags(): List<String>
}
