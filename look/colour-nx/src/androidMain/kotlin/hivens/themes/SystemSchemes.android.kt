// The application's context is one of the host's own services, which it hands the modules it trusts.
@file:OptIn(HostApi::class)

package hivens.themes

import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import hivens.widget.api.HostApi
import hivens.widget.api.ServiceContext
import hivens.widget.api.find
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * On a phone, the night mode of the configuration the application runs in, which the host hands over
 * as its context. Unreadable where the host hands none.
 */
internal actual fun platformSchemeReader(context: ServiceContext): SystemSchemeReader =
    context.hostServices.find<Context>()?.let { ConfigurationScheme(it.applicationContext) } ?: UnreadableScheme

/**
 * The application's configuration as it is now and as the system changes it. The application hears
 * every change of the night mode, whether or not the activity takes the change itself.
 */
private class ConfigurationScheme(private val application: Context) : SystemSchemeReader {
    override suspend fun probe(): Boolean = application.resources.configuration.isNight()

    override fun observe(): Flow<Boolean?> = callbackFlow {
        val callbacks = object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) {
                trySend(newConfig.isNight())
            }

            @Suppress("OVERRIDE_DEPRECATION")
            override fun onLowMemory() = Unit
        }
        // Registered before the first read, so a change between the two is not lost.
        application.registerComponentCallbacks(callbacks)
        trySend(application.resources.configuration.isNight())
        awaitClose { application.unregisterComponentCallbacks(callbacks) }
    }.distinctUntilChanged()
}

private fun Configuration.isNight(): Boolean = (uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
