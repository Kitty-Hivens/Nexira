package hivens.ui.pictures

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalDensity
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.compose.LocalPlatformContext
import coil3.decode.BlackholeDecoder
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.size.Precision
import coil3.size.Scale
import hivens.core.api.interfaces.IPackRepository
import hivens.launcher.sources.PackArtResolver
import hivens.ui.theme.Dimens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Loads [url] into memory ahead of being drawn, at the size it will be drawn filling, so
 * whoever draws it finds it decoded.
 */
internal fun ImageLoader.warm(context: PlatformContext, url: String, width: Int, height: Int) {
    val _ = enqueue(
        ImageRequest.Builder(context)
            .data(url)
            .size(width, height)
            .scale(Scale.FILL)
            .precision(Precision.INEXACT)
            .build(),
    )
}

/**
 * Loads [url] onto the disk ahead of being drawn and decodes nothing: for a picture too
 * large to hold decoded on the chance that its screen is opened.
 */
@OptIn(ExperimentalCoilApi::class)
internal fun ImageLoader.keep(context: PlatformContext, url: String) {
    val _ = enqueue(
        ImageRequest.Builder(context)
            .data(url)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .decoderFactory(BlackholeDecoder.Factory())
            .build(),
    )
}

/**
 * Has the covers of the installed packs loaded before a screen that draws them is opened:
 * the icons decoded, the banners on disk. After [afterStart], so it does not compete with
 * what the first screen draws itself, and again for a pack installed later.
 */
@Composable
internal fun WarmLibrary(afterStart: Duration = 1.seconds) {
    val packs: IPackRepository = koinInject()
    val art: PackArtResolver = koinInject()
    val context = LocalPlatformContext.current
    val icon = with(LocalDensity.current) { Dimens.packAvatar.roundToPx() }
    LaunchedEffect(packs, art, context, icon) {
        delay(afterStart)
        val loader = SingletonImageLoader.get(context)
        val asked = HashSet<String>()
        packs.observe().collectLatest { library ->
            library.forEach { pack ->
                val cover = withContext(Dispatchers.IO) { art.resolve(pack) }
                cover.iconUrl?.takeIf { it.isNotBlank() && asked.add(it) }?.let { loader.warm(context, it, icon, icon) }
                cover.bannerUrl?.takeIf { it.isNotBlank() && asked.add(it) }?.let { loader.keep(context, it) }
            }
        }
    }
}
