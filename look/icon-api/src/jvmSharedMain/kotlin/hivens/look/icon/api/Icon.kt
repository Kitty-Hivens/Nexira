package hivens.look.icon.api

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.inset
import hivens.look.manager.api.LookAxis

/**
 * The icon axis: how icons are drawn, asked by name, answered as one [IconAnswers] by the provider the
 * person's choice names, and by [IconAnswers.BASE] when no provider is chosen for the axis. A widget
 * draws one with [Draw], or into its own drawing with [drawIcon], in its own size and colour.
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
        val style = remember(tint, fill, weight) { styled(tint, fill, weight) }
        Spacer(modifier.fillMaxSize().drawBehind { if (drawing != null) with(drawing) { draw(style) } })
    }
}

/**
 * [drawing] in a room of [size] at [topLeft], by default all of this scope, for a component that draws
 * itself. It is had from [Icon.drawing] in composition, and outside of it from the look's snapshot.
 */
fun DrawScope.drawIcon(
    drawing: IconDrawing,
    tint: Color,
    topLeft: Offset = Offset.Zero,
    size: Size = Size(this.size.width - topLeft.x, this.size.height - topLeft.y),
    fill: Float = 0f,
    weight: Int = 400,
) {
    val style = styled(tint, fill, weight)
    inset(
        left = topLeft.x,
        top = topLeft.y,
        right = this.size.width - topLeft.x - size.width,
        bottom = this.size.height - topLeft.y - size.height,
    ) { with(drawing) { draw(style) } }
}

private fun styled(tint: Color, fill: Float, weight: Int) = IconStyle(tint, fill.coerceIn(0f, 1f), weight.coerceIn(100, 700))
