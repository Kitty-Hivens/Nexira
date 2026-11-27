package hivens.widget.model

private const val STANDARD_DOCUMENT_RESOURCE = "/widget/standard-document.json"

/**
 * The document the launcher ships: the interface of whoever has none of their own. Part of the jar, so one that cannot
 * be read is a broken build and throws.
 *
 * It says what each composition asks of its window, what each config holds and, in the pages host, what each screen
 * holds.
 */
object StandardDocument {
    fun load(): Document {
        val stream = checkNotNull(StandardDocument::class.java.getResourceAsStream(STANDARD_DOCUMENT_RESOURCE)) {
            "The standard document is not on the classpath at $STANDARD_DOCUMENT_RESOURCE"
        }
        val text = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        return when (val read = DocumentCodec.read(text)) {
            is DocumentRead.Whole -> read.document
            is DocumentRead.Newer -> error("The standard document is schema ${read.schema}, this build reads $DOCUMENT_SCHEMA")
            is DocumentRead.Outdated -> error("The standard document is schema ${read.schema}, this build reads $DOCUMENT_SCHEMA")
            is DocumentRead.Broken -> error("The standard document cannot be read: ${read.reason}")
        }
    }
}
