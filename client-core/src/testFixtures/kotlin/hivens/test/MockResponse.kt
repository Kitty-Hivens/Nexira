package hivens.test

import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
import kotlinx.serialization.json.Json

/**
 * A single mocked HTTP response definition.
 *
 * @param urlContains substring to match against request URL; null matches any request
 * @param status      HTTP status code to return
 * @param body        response body (JSON or plain text)
 * @param contentType Content-Type header value
 */
data class MockResponse(
    val urlContains: String? = null,
    val status: HttpStatusCode = HttpStatusCode.OK,
    val body: String = "",
    val contentType: ContentType = ContentType.Application.Json
)

/**
 * Builds a test [HttpClient] backed by [MockEngine].
 *
 * Responses are matched against [responses] in order by [MockResponse.urlContains].
 * The first matching entry is returned; if nothing matches, the last entry is used as fallback.
 * Matched entries are consumed from the queue, enabling sequential multi-step scenarios:
 *
 * ```kotlin
 * buildMockClient(
 *     MockResponse(urlContains = "loader",         body = UPDATE_BODY),
 *     MockResponse(urlContains = "smartycraft.jar", body = "fakebytes"),
 *     MockResponse(urlContains = "loader",         body = OK_BODY),
 * )
 * ```
 */
fun buildMockClient(vararg responses: MockResponse): HttpClient {
    val queue = responses.toMutableList()

    return HttpClient(MockEngine) {
        engine {
            addHandler { request ->
                val url = request.url.toString()
                val match = queue.firstOrNull { it.urlContains == null || url.contains(it.urlContains) }
                    ?: queue.last()

                if (queue.size > 1 && match == queue.first()) queue.removeAt(0)

                respond(
                    content = ByteReadChannel(match.body.toByteArray()),
                    status = match.status,
                    headers = headersOf(HttpHeaders.ContentType, match.contentType.toString())
                )
            }
        }

        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
                encodeDefaults = true
            })
        }
    }
}

fun buildMockClient(
    body: String,
    status: HttpStatusCode = HttpStatusCode.OK,
    contentType: ContentType = ContentType.Application.Json
): HttpClient = buildMockClient(MockResponse(body = body, status = status, contentType = contentType))

fun buildErrorClient(status: HttpStatusCode = HttpStatusCode.InternalServerError): HttpClient =
    buildMockClient(body = """{"error":"server error"}""", status = status)
