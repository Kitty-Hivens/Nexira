package hivens.ui.screens.custom

import hivens.widget.model.WidgetInstance
import hivens.widget.model.WidgetKind
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * The links that open a screen somebody made: one widget kind, found by the config id in its props. The model knows
 * configs and nothing of which widgets point at them, so what a link is, is said here, where the kind is known.
 */
internal object ScreenLinks {
    val KIND = WidgetKind("nav.screen")

    /**
     * The id a link widget points at, or null when [widget] is not a link. A prop of
     * the wrong shape is no link either, rather than a throw inside a reset or a
     * screen delete: the layout file is somebody's to edit.
     */
    fun target(widget: WidgetInstance): String? =
        if (widget.kind != KIND) null else (widget.props["screen"] as? JsonPrimitive)?.contentOrNull
}
