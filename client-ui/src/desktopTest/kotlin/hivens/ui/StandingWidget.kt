package hivens.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import hivens.widget.api.LocalDocument
import hivens.widget.api.LocalSlotPath
import hivens.widget.model.Composition
import hivens.widget.model.CompositionName
import hivens.widget.model.Config
import hivens.widget.model.ConfigId
import hivens.widget.model.ConfigLayout
import hivens.widget.model.Document
import hivens.widget.model.FamilyId
import hivens.widget.model.LayoutGraph
import hivens.widget.model.LayoutId
import hivens.widget.model.NodePath
import hivens.widget.model.NodeRoot
import hivens.widget.model.SlotContent
import hivens.widget.model.SlotId
import hivens.widget.model.SurfaceId
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
internal fun documentOf(vararg widgets: WidgetInstance): Document = Document(
    start = STANDING,
    compositions = mapOf(
        STANDING to Composition(
            start = STANDING_CONFIG,
            configs = mapOf(STANDING_CONFIG to Config(layouts = listOf(ConfigLayout(STANDING_LAYOUT, SlotContent(widgets.toList()))))),
        ),
    ),
)

/** A widget of [kind] holding the slots the bundled layout gave [surface], the surface it was drawn from before. */
internal fun LayoutGraph.containerOf(kind: String, surface: String): WidgetInstance =
    WidgetInstance(WidgetKind(kind), kind, children = surfaces[SurfaceId(surface)]?.slotsOf(FamilyId.GENERAL).orEmpty())

/** A widget of [kind] holding [slots]. */
internal fun containerOf(kind: String, vararg slots: Pair<String, SlotContent>): WidgetInstance =
    WidgetInstance(WidgetKind(kind), kind, children = slots.associate { (slot, content) -> SlotId(slot) to content })

private val STANDING = CompositionName("standing")
private val STANDING_CONFIG = ConfigId("standing")
private val STANDING_LAYOUT = LayoutId("main")

/** The root of the one config [documentOf] makes. */
internal val STANDING_ROOT = NodePath(STANDING, NodeRoot.Layout(STANDING_CONFIG, STANDING_LAYOUT))
