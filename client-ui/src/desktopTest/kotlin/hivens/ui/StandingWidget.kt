package hivens.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import hivens.widget.api.SlotRenderer
import hivens.widget.api.LocalDocument
import hivens.widget.api.LocalSlotPath
import hivens.widget.model.Composition
import hivens.widget.model.CompositionName
import hivens.widget.model.Config
import hivens.widget.model.ConfigId
import hivens.widget.model.ConfigLayout
import hivens.widget.model.Document
import hivens.widget.model.LayoutId
import hivens.widget.model.NodePath
import hivens.widget.model.NodeRoot
import hivens.widget.model.SlotContent
import hivens.widget.model.SlotId
import hivens.widget.model.StandardDocument
import hivens.widget.model.WidgetInstance
import hivens.widget.model.WidgetKind

/**
 * Draws [content] for a container that stands alone in the one config of a document of its own, the way a composition
 * would: what the container's slots hold is what [instance] holds.
 */
@Composable
internal fun Standing(instance: WidgetInstance, content: @Composable (WidgetInstance) -> Unit) {
    val document = remember(instance) { documentOf(instance) }
    CompositionLocalProvider(LocalDocument provides document, LocalSlotPath provides STANDING_ROOT) {
        content(instance)
    }
}

/** A document whose one config holds [widgets] at its root, at [STANDING_ROOT]. */
internal fun documentOf(vararg widgets: WidgetInstance): Document = documentOf(SlotContent(widgets.toList()))

/**
 * The widget of [kind] the standard document ships, with what its slots hold there: a screen as the launcher shows it.
 * A widget the composition has once is its shared record, since what stands in a layout is only a reference to it.
 */
internal fun standardContainer(kind: String): WidgetInstance {
    val document = StandardDocument.load()
    fun find(content: SlotContent): WidgetInstance? =
        content.widgets.firstOrNull { it.kind.value == kind } ?: content.widgets.firstNotNullOfOrNull { w -> w.children.values.firstNotNullOfOrNull(::find) }
    val found = document.compositions.values.firstNotNullOfOrNull { c -> c.singles[WidgetKind(kind)] }
        ?: document.compositions.values.firstNotNullOfOrNull { c ->
            c.singles.values.firstNotNullOfOrNull { single -> single.children.values.firstNotNullOfOrNull(::find) }
                ?: c.configs.values.firstNotNullOfOrNull { config -> config.layouts.firstNotNullOfOrNull { find(it.root) } }
        }
    return checkNotNull(found) { "the standard document has no $kind" }
}

/** What the slot [slot] of the standard document's [kind] holds. */
internal fun standardSlot(kind: String, slot: String): SlotContent =
    checkNotNull(standardContainer(kind).children[SlotId(slot)]) { "$kind has no slot $slot in the standard document" }

/** The right rail's column as the screen [screen] shows it: the widgets of the one record that are shown there. */
internal fun standardRail(screen: String): SlotContent {
    val rail = standardSlot("appshell.region.right", "main")
    val config = StandardDocument.load().compositions.getValue(CompositionName("launcher")).start
    return rail.copy(widgets = rail.widgets.filter { it.shownAt(setOf(config.value, screen)) })
}

/** Draws [content] as the root slot of a document of its own, the way a config's root is drawn. */
@Composable
internal fun DocumentSlot(content: SlotContent, modifier: Modifier = Modifier, spacing: Dp = 0.dp) {
    val document = remember(content) { documentOf(content) }
    CompositionLocalProvider(LocalDocument provides document) {
        SlotRenderer(STANDING_ROOT, modifier, spacing)
    }
}

/** A document whose one config holds [content] at its root. */
internal fun documentOf(content: SlotContent): Document = Document(
    start = STANDING,
    compositions = mapOf(
        STANDING to Composition(start = STANDING_CONFIG, configs = mapOf(STANDING_CONFIG to Config(layouts = listOf(ConfigLayout(STANDING_LAYOUT, content))))),
    ),
)

/** A widget of [kind] holding [slots]. */
internal fun containerOf(kind: String, vararg slots: Pair<String, SlotContent>): WidgetInstance =
    WidgetInstance(WidgetKind(kind), kind, children = slots.associate { (slot, content) -> SlotId(slot) to content })

private val STANDING = CompositionName("standing")
private val STANDING_CONFIG = ConfigId("standing")
private val STANDING_LAYOUT = LayoutId("main")

/** The root of the one config [documentOf] makes. */
internal val STANDING_ROOT = NodePath(STANDING, NodeRoot.Layout(STANDING_CONFIG, STANDING_LAYOUT))
