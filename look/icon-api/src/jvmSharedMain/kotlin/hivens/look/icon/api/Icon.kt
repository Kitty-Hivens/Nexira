package hivens.look.icon.api

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import hivens.look.manager.api.LookAxis

/**
 * The icon axis: how icons are drawn, asked by name, answered as one [IconAnswers] by the provider the
 * person's choice names, and by [IconAnswers.BASE] when no provider is chosen for the axis. A widget
 * draws one with [Draw], in its own size and colour.
 *
 * A pack and the person's choice name it by [IconApi.AXIS], and a provider answers it by naming that id
 * as its axis and [IconApi.VERSION] as its contract.
 */
object Icon : LookAxis<IconAnswers>(IconApi.AXIS, IconApi.VERSION, IconAnswers::class, IconAnswers.BASE) {
    override val label: String = IconModule.NAME

    /** What draws [key] in this look, null where nothing does, see [IconAnswers.drawing]. */
    @Composable
    @ReadOnlyComposable
    fun drawing(key: IconKey): IconDrawing? = local.current.drawing(key)

    /**
     * [key] drawn in the room [modifier] gives it, which is all the room it gets: the component says the
     * size there. Blank where nothing draws it, in the same room.
     */
    @Composable
    fun Draw(key: IconKey, modifier: Modifier, tint: Color, fill: Float = 0f, weight: Int = 400) {
        val drawing = drawing(key)
        val style = remember(tint, fill, weight) { IconStyle(tint, fill.coerceIn(0f, 1f), weight.coerceIn(100, 700)) }
        Box(modifier, propagateMinConstraints = true) { drawing?.Draw(style) }
    }
}
