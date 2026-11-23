package hivens.look.type.api

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import hivens.look.manager.api.LookAxis

/**
 * The type axis: the faces text is drawn in, asked by what the text is, answered as one [TypeAnswers]
 * by the provider the person's choice names, and by [TypeAnswers.BASE] when no provider is chosen for
 * the axis. A widget asks through the members here, which read [local], and draws in its own sizes.
 *
 * A pack and the person's choice name it by [TypeApi.AXIS], and a provider answers it by naming that
 * id as its axis and [TypeApi.VERSION] as its contract.
 */
object Type : LookAxis<TypeAnswers>(TypeApi.AXIS, TypeApi.VERSION, TypeAnswers::class, TypeAnswers.BASE) {
    override val label: String = TypeModule.NAME

    /** See [TypeAnswers.face]. */
    @Composable
    @ReadOnlyComposable
    fun face(role: TextRole): TypeFace = local.current.face(role)

    /** See [TypeAnswers.mono]. */
    val mono: TypeFace
        @Composable @ReadOnlyComposable
        get() = local.current.mono

    /** See [TypeAnswers.forText]. */
    @Composable
    @ReadOnlyComposable
    fun forText(text: String): TypeFace? = local.current.forText(text)
}
