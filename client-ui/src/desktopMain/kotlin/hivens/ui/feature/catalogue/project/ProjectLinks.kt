package hivens.ui.feature.catalogue.project

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import hivens.core.data.PackOrigin
import hivens.ui.Screen
import hivens.ui.navigation.NavRequests
import hivens.ui.render.openInBrowser
import org.koin.compose.koinInject

/**
 * The catalogue's own project routes, as they appear in a URL.
 *
 * Modrinth types its project pages by what the project IS, and every one of them
 * is a page this launcher can draw itself. Anything else under the same host --
 * a user, a collection, the settings -- is not, and goes out to a browser like
 * any other address.
 */
private val PROJECT_ROUTES = setOf("mod", "plugin", "datapack", "resourcepack", "shader", "modpack")

/**
 * A catalogue project a URL points at: its slug, and whether it is a modpack, which
 * opens as a pack rather than as a project to install into one.
 */
data class ModrinthLink(val slug: String, val modpack: Boolean)

/**
 * The project a catalogue URL points at, or null when it points at anything else.
 *
 * Deliberately tolerant about the shape of the address and strict about the host:
 * a link is followed into the app only when it is certain what it leads to, and
 * a guess that lands a reader on the wrong page is worse than a browser window.
 */
fun modrinthProjectLink(url: String): ModrinthLink? {
    val withoutScheme = url.substringAfter("://", url)
    val host = withoutScheme.substringBefore('/').removePrefix("www.").lowercase()
    if (host != "modrinth.com") return null
    val path = withoutScheme.substringAfter('/', "").substringBefore('?').substringBefore('#')
    val parts = path.split('/').filter { it.isNotBlank() }
    val route = parts.firstOrNull()?.lowercase()
    if (parts.size < 2 || route !in PROJECT_ROUTES) return null
    // `/mod/sodium` and `/mod/sodium/versions` both name the same project; only
    // the slug decides where the reader lands.
    val slug = parts[1].takeIf { it.isNotBlank() } ?: return null
    return ModrinthLink(slug, modpack = route == MODPACK_ROUTE)
}

/** [modrinthProjectLink]'s slug alone. */
fun modrinthProjectSlug(url: String): String? = modrinthProjectLink(url)?.slug

private const val MODPACK_ROUTE = "modpack"

/**
 * A project's own address in the catalogue.
 *
 * The route is `mod` whatever the project actually is: the catalogue answers an
 * id under any of its project routes, and this address exists to be handed
 * straight back to [modrinthProjectSlug], not to be shown to anybody.
 */
fun projectUrl(projectId: String): String = "https://modrinth.com/mod/$projectId"

/**
 * How a link is followed here.
 *
 * A composition local rather than something pulled out of the graph, because the
 * callers are a markdown body and two rail widgets, and a widget must be able to
 * DRAW without an application around it -- a render sheet stands one up from
 * fixtures and has no navigation to offer it.
 *
 * The default is the browser, which is what a link does when nothing claims it.
 * The shell provides the version that keeps a reader inside the app.
 */
val LocalLinkFollower: ProvidableCompositionLocal<(String) -> Unit> =
    compositionLocalOf { { url: String -> openInBrowser(url) } }

/**
 * Follows a link the way a reader expects: a mod opens as a page HERE, everything
 * else opens where links open.
 *
 * The launcher draws project pages of its own now, so handing a link to one out
 * to a browser sends the reader out of the app to read a page the app was already
 * able to show them -- and loses the pack they were installing into on the way.
 */
@Composable
fun rememberLinkFollower(): (String) -> Unit = LocalLinkFollower.current

/**
 * The shell's own follower: a catalogue project becomes a navigation, a modpack to
 * its pack page, and anything else goes out to the browser.
 */
@Composable
fun rememberNavigatingLinkFollower(): (String) -> Unit {
    val uriHandler: UriHandler = LocalUriHandler.current
    val nav: NavRequests = koinInject()
    return remember(uriHandler, nav) {
        { url: String ->
            val link = modrinthProjectLink(url)
            when {
                link == null -> uriHandler.openUri(url)
                link.modpack -> nav.open(Screen.CataloguePackDetail(PackOrigin.Modrinth, link.slug))
                else -> nav.open(Screen.ModDetail(ModTarget.Catalogue(link.slug)))
            }
        }
    }
}
