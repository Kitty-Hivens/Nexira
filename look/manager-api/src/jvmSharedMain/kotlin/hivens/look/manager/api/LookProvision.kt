package hivens.look.manager.api

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The answers of every axis, as one value a composition is wrapped in: what [LookKeys.Provide] answers.
 *
 * The host wraps everything it draws in [Provide] and knows nothing of what is inside: no axis, no
 * provider, no manager. A widget reads an axis's answers through the axis's own [LookAxis.local], and
 * wraps a subtree in another look with [OverrideLook]. The value stays the same while the answers
 * change, so a change of one axis recomposes only what reads that axis.
 */
@Stable
interface LookProvision {
    /** [content] with every axis's answers provided, and this provision for [OverrideLook] to nest in. */
    @Composable
    fun Provide(content: @Composable () -> Unit)

    /**
     * [content] in the look the person would have if they chose [choice] on top of their own (see
     * [LookChoice.overlaidWith]): every axis goes down the whole choice again, the subtree's family for
     * it, the person's own, the subtree's pack or else the person's, the default pack, the base. The
     * providers it needs are opened off the drawing thread while the subtree is drawn, and until they
     * answer the subtree keeps the look around it.
     */
    @Composable
    fun Override(choice: LookChoice, content: @Composable () -> Unit)

    companion object {
        /** No answers at all, for a host with no manager: every axis reads its base answers. */
        val NONE: LookProvision = object : LookProvision {
            @Composable
            override fun Provide(content: @Composable () -> Unit) = content()

            @Composable
            override fun Override(choice: LookChoice, content: @Composable () -> Unit) = content()
        }
    }
}

/** The provision the composition is drawn in, which [OverrideLook] nests in. [LookProvision.NONE] outside any. */
val LocalLookProvision = staticCompositionLocalOf { LookProvision.NONE }

/** [content] in the look [choice] makes of the one around it, see [LookProvision.Override]. */
@Composable
fun OverrideLook(choice: LookChoice, content: @Composable () -> Unit) = LocalLookProvision.current.Override(choice, content)
