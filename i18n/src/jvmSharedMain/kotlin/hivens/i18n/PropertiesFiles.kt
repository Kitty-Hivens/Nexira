package hivens.i18n

import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.io.StringReader
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/**
 * The `.properties` files words are kept in, read and written as UTF-8 rather than the
 * Latin-1 the JDK defaults to for a stream, so a translator writes their own script as it is.
 */
object PropertiesFiles {

    fun read(reader: Reader): Map<String, String> {
        val properties = Properties()
        properties.load(reader)
        return properties.stringPropertyNames().associateWith { properties.getProperty(it) }
    }

    fun read(stream: InputStream): Map<String, String> = InputStreamReader(stream, Charsets.UTF_8).use(::read)

    fun read(file: Path): Map<String, String> = Files.newInputStream(file).use(::read)

    /** What [readChecked] found: the entries, and whether the bytes were not all UTF-8. */
    class Checked(val entries: Map<String, String>, val notUtf8: Boolean)

    /**
     * [file] read as [read] reads it, saying as well whether it held bytes that are not UTF-8. Those
     * are read as the replacement character, so the rest of the file still counts, and the caller says
     * which file it was rather than letting the damage pass unseen.
     */
    fun readChecked(file: Path): Checked {
        val bytes = Files.readAllBytes(file)
        val strict = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val (text, notUtf8) = try {
            strict.decode(ByteBuffer.wrap(bytes)).toString() to false
        } catch (_: CharacterCodingException) {
            String(bytes, Charsets.UTF_8) to true
        }
        return Checked(read(StringReader(text)), notUtf8)
    }

    /**
     * [entries] as the text of a file, in the order given, one `key=value` a line. Only what the
     * format needs is escaped: the backslash, line breaks, a leading space and the separators in a
     * key. Every other character is written as itself.
     */
    fun write(entries: Map<String, String>, header: List<String> = emptyList()): String = buildString {
        header.forEach { append("# ").append(it).append('\n') }
        if (header.isNotEmpty()) append('\n')
        entries.forEach { (key, value) ->
            append(escape(key, isKey = true)).append('=').append(escape(value, isKey = false)).append('\n')
        }
    }

    private fun escape(text: String, isKey: Boolean): String = buildString {
        text.forEachIndexed { i, c ->
            when {
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c == '\u000c' -> append("\\f")
                c == ' ' && (isKey || i == 0) -> append("\\ ")
                isKey && (c == '=' || c == ':' || c == '#' || c == '!') -> append('\\').append(c)
                !isKey && i == 0 && (c == '#' || c == '!') -> append('\\').append(c)
                c.isISOControl() -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
    }
}
