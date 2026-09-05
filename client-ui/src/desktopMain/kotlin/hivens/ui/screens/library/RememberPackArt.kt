package hivens.ui.screens.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import hivens.core.data.PackInstance
import hivens.launcher.catalogue.PackArt
import hivens.launcher.catalogue.PackArtResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koin.compose.koinInject

/**
 * Native cover for an instance, resolved through [PackArtResolver]. Art captured
 * at install is returned immediately; otherwise it resolves off-thread and the
 * card/hero shows its pixel placeholder until the real cover lands (or stays on
 * it if the source has none). Cached in the resolver.
 *
 * Keyed on the art the record carries as well as its id: an update that captures
 * a new cover rewrites the same instance, and produceState keeps its last value
 * across a key change -- so the initial value is re-applied here rather than left
 * showing the cover the pack used to have.
 */
@Composable
fun rememberPackArt(instance: PackInstance): PackArt {
    val resolver: PackArtResolver = koinInject()
    // Seeded with what is already known, so a pack the resolver has answered once
    // paints its cover on the first frame. It used to start from the instance's own
    // (usually absent) urls and reach the cache only after a dispatcher hop, so
    // arriving on a page always showed the placeholder first and swapped it out --
    // the cover appearing after the navigation rather than with it.
    val known = remember(instance.id, instance.iconUrl, instance.bannerUrl) { resolver.cached(instance) }
    val art by produceState(
        initialValue = known ?: PackArt.NONE,
        key1         = instance.id,
        key2         = instance.iconUrl,
        key3         = instance.bannerUrl,
    ) {
        if (known != null) {
            value = known
            return@produceState
        }
        value = withContext(Dispatchers.IO) { resolver.resolve(instance) }
    }
    return art
}
