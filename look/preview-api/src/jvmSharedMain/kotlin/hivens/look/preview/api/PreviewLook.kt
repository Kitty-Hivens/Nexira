package hivens.look.preview.api

import androidx.compose.runtime.Composable
import java.util.ServiceLoader

/**
 * What a preview or a render in a test takes from outside the module that draws it: the answers of the
 * axes, whose providers that module does not know. It wraps what it draws in the one that is on the
 * class path, found as a service of this interface, and only where no look is around.
 *
 * An implementation is on the class path of previews and tests and never of the application, which draws
 * in the look the person chose.
 */
interface PreviewLook {
    /** [content] with the answers it brings, dark or light by [dark], with decorative movement held still by [reduceMotion]. */
    @Composable
    fun Provide(dark: Boolean, reduceMotion: Boolean, content: @Composable () -> Unit)

    companion object {
        /** The one on the class path, null where there is none. */
        val found: PreviewLook? by lazy {
            ServiceLoader.load(PreviewLook::class.java, PreviewLook::class.java.classLoader).firstOrNull()
        }
    }
}
