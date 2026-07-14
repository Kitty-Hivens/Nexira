package hivens.ui.screens.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
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
 * it if the source has none). Keyed on [PackInstance.id], cached in the resolver.
 */
@Composable
fun rememberPackArt(instance: PackInstance): PackArt {
    val resolver: PackArtResolver = koinInject()
    val art by produceState(
        initialValue = PackArt(instance.iconUrl, instance.bannerUrl),
        key1         = instance.id,
    ) {
        if (instance.iconUrl == null && instance.bannerUrl == null) {
            value = withContext(Dispatchers.IO) { resolver.resolve(instance) }
        }
    }
    return art
}
