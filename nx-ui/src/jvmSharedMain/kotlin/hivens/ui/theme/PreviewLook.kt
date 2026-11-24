package hivens.ui.theme

import androidx.compose.runtime.Composable
import java.util.ServiceLoader

/**
 * What a preview or a render in a test takes from outside this module: the answers of an axis whose
 * provider this module does not know, the icon library's say. [NxThemed] wraps what it draws in the one
 * that is on the class path, found as a service of this interface, and only where no look is around.
 *
 * An implementation is on the class path of previews and tests and never of the application, which draws
 * in the look the person chose.
 */
interface PreviewLook {
    /** [content] with the answers it brings. */
    @Composable
    fun Provide(content: @Composable () -> Unit)

    companion object {
        /** The one on the class path, null where there is none. */
        internal val found: PreviewLook? by lazy {
            ServiceLoader.load(PreviewLook::class.java, PreviewLook::class.java.classLoader).firstOrNull()
        }
    }
}
