package hivens.widget.model

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** What reading a document's text came to. */
sealed interface DocumentRead {
    data class Whole(val document: Document) : DocumentRead

    /** Written by a build that knows [schema], which this one does not. It is left as it is. */
    data class Newer(val schema: Int) : DocumentRead

    /** Written in an older shape, [schema], which this build no longer draws: nothing of it carries over. */
    data class Outdated(val schema: Int) : DocumentRead

    /** Not a document, with why. */
    data class Broken(val reason: String) : DocumentRead
}

/** A document's text. What a newer build added beside the fields this one knows is passed over. */
object DocumentCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    fun write(document: Document): String = json.encodeToString(Document.serializer(), document)

    fun read(text: String): DocumentRead = try {
        val element = json.parseToJsonElement(text) as? JsonObject
        val schema = (element?.get("schema") as? JsonPrimitive)?.intOrNull
        when {
            element == null -> DocumentRead.Broken("not an object")
            schema == null -> DocumentRead.Broken("no schema")
            schema > DOCUMENT_SCHEMA -> DocumentRead.Newer(schema)
            schema < DOCUMENT_SCHEMA -> DocumentRead.Outdated(schema)
            else -> DocumentRead.Whole(json.decodeFromJsonElement(Document.serializer(), element))
        }
    } catch (e: SerializationException) {
        DocumentRead.Broken(e.message.orEmpty())
    } catch (e: IllegalArgumentException) {
        DocumentRead.Broken(e.message.orEmpty())
    }
}
