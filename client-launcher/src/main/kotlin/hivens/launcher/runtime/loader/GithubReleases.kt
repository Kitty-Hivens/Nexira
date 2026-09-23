package hivens.launcher.runtime.loader

import hivens.core.api.HttpClientProvider
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException

/**
 * The releases of a GitHub repository that carry [asset], newest first, as loader
 * versions to pick from.
 *
 * Only for a picker. GitHub rate-limits unauthenticated calls per address, so a
 * launch never asks it, and a pack names its version instead of resolving a
 * latest here. A release flagged pre-release, or tagged alpha or beta, is marked
 * unstable: Cleanroom tags every build `-alpha` without the flag.
 */
internal suspend fun githubReleaseVersions(
    clientProvider: HttpClientProvider,
    json: Json,
    apiUrl: String,
    asset: (tag: String) -> String,
): List<LoaderVersionOption> {
    val text = clientProvider.current.prepareGet(apiUrl).execute { resp ->
        if (!resp.status.isSuccess()) throw IOException("GET $apiUrl -> HTTP ${resp.status}")
        resp.bodyAsText()
    }
    return json.parseToJsonElement(text).jsonArray.mapNotNull { element ->
        val release = element.jsonObject
        val tag = release["tag_name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
        val assets = release["assets"]?.jsonArray.orEmpty().mapNotNull { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull }
        if (asset(tag) !in assets) return@mapNotNull null
        val flagged = release["prerelease"]?.jsonPrimitive?.boolean == true
        LoaderVersionOption(tag, stable = !flagged && UNSTABLE.find(tag) == null)
    }
}

private val UNSTABLE = Regex("(?i)(alpha|beta|rc|pre)")
