package hivens.ui.platform

import android.content.Context

/**
 * The application context for the pieces of shared code that act on the host without a
 * composition to ask: opening a link, writing the clipboard.
 *
 * Set once by the Application before anything can call them.
 */
object AndroidAppContext {
    @Volatile
    private var application: Context? = null

    fun install(context: Context) {
        application = context.applicationContext
    }

    internal fun require(): Context = checkNotNull(application) { "AndroidAppContext.install was not called" }
}
