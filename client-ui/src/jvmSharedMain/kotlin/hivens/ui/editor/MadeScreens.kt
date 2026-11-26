package hivens.ui.editor

import hivens.ui.screens.custom.ScreenLinks
import hivens.widget.model.CompositionName
import hivens.widget.model.Config
import hivens.widget.model.ConfigId
import hivens.widget.model.Document
import hivens.widget.model.SlotContent
import hivens.widget.model.SlotId
import hivens.widget.model.WidgetInstance
import hivens.widget.model.WidgetKind
import hivens.widget.model.flatMapWidgets
import hivens.widget.model.widgets
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Screens somebody made, as configs of the document: a new one is laid out like the composition's start config with
 * nothing on its pages, and the left rail carries a link to it.
 *
 * What a page is, the widgets' declarations say ([pagesOf]). Which widget is the rail and which kind is a link is the
 * launcher's own knowledge, kept here and nowhere in the model.
 */
internal object MadeScreens {
    /** The rail's region, and the slot a new screen's link goes into, at its end. */
    private val RAIL = WidgetKind("appshell.region.left")
    private val RAIL_SLOT = SlotId("top")

    /**
     * A config laid out like [composition]'s start config, every page in it, a slot that holds a page and that the
     * config holds as its own, empty. Null where the composition has no start config to take the frame from.
     */
    fun blank(document: Document, composition: CompositionName, title: String, icon: String, pagesOf: (WidgetKind) -> List<SlotId>): Config? {
        val start = document.compositions[composition]?.startConfig ?: return null
        return Config(title = title, icon = icon, layouts = start.layouts.map { it.copy(root = withoutPages(it.root, pagesOf)) })
    }

    /** [content] with what every page in it holds gone, the pages themselves kept. */
    fun withoutPages(content: SlotContent, pagesOf: (WidgetKind) -> List<SlotId>): SlotContent = content.copy(
        widgets = content.widgets.map { w ->
            val pages = pagesOf(w.kind)
            w.copy(children = w.children.mapValues { (slot, held) -> if (slot in pages) SlotContent() else withoutPages(held, pagesOf) })
        },
    )

    /** [document] with a link to [config] at the end of the rail, unless one stands anywhere in [composition] already. */
    fun ensureLink(document: Document, composition: CompositionName, config: ConfigId): Document {
        val current = document.compositions[composition] ?: return document
        if (current.widgets().any { ScreenLinks.target(it) == config.value }) return document
        val rail = current.singles[RAIL] ?: return document
        val slot = rail.children[RAIL_SLOT] ?: return document
        val link = WidgetInstance(
            kind = ScreenLinks.KIND,
            instanceId = "screen-link-${config.value}",
            props = buildJsonObject { put("screen", JsonPrimitive(config.value)) },
        )
        val record = rail.copy(children = rail.children + (RAIL_SLOT to slot.copy(widgets = slot.widgets + link)))
        return document.copy(compositions = document.compositions + (composition to current.copy(singles = current.singles + (RAIL to record))))
    }

    /** [document] with every link to [config] in [composition] gone. */
    fun removeLinks(document: Document, composition: CompositionName, config: ConfigId): Document =
        document.flatMapWidgets(composition) { w -> if (ScreenLinks.target(w) == config.value) emptyList() else listOf(w) }
}
