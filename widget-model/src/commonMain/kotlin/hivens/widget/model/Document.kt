package hivens.widget.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The schema of [Document] this build reads and writes. */
const val DOCUMENT_SCHEMA: Int = 1

@JvmInline
@Serializable
value class CompositionName(val value: String)

@JvmInline
@Serializable
value class ConfigId(val value: String)

@JvmInline
@Serializable
value class LayoutId(val value: String)

/**
 * The whole interface as data: the modules it needs, the compositions a window can show, and the one to start.
 *
 * A window is named nowhere in it. A window registers itself with the host when something opens it, and shows a
 * composition of this document.
 *
 * [modules] are the ids of the modules the host brings in before it draws, and a key of a module not listed is
 * absent. The list is the document's own: the standard one is replaced whole by each launcher update, and a person's
 * own is never added to.
 */
@Serializable
data class Document(
    @EncodeDefault val schema: Int = DOCUMENT_SCHEMA,
    val modules: List<String> = emptyList(),
    val start: CompositionName,
    val compositions: Map<CompositionName, Composition> = emptyMap(),
) {
    /** The composition [start] names, or the first there is where it names none, or null in a document with none. */
    val startComposition: Composition?
        get() = compositions[start] ?: compositions.values.firstOrNull()
}

/**
 * What lives in a window while what it draws changes: its configs, of which it draws one at a time, and what it asks
 * of the window.
 *
 * [singles] are the widgets this composition has once. Every config that has one has the same one, so its record is
 * kept here and a config only says where it stands ([standing]). Moving to another config neither makes it anew nor
 * changes it. A config may hold a slot of one as its own: what stands there is then that config's, and the record's
 * content of that slot is what the other configs show.
 *
 * [residents] are drawn on the window's top layer over whichever config is shown. [transition] is the widget that is
 * handed the config going and the config coming, null for a plain swap.
 */
@Serializable
data class Composition(
    val start: ConfigId,
    val frame: Frame = Frame(),
    val configs: Map<ConfigId, Config> = emptyMap(),
    val singles: Map<WidgetKind, WidgetInstance> = emptyMap(),
    val residents: SlotContent = SlotContent(flow = null),
    val transition: WidgetInstance? = null,
) {
    /** The config [start] names, or the first there is where it names none, or null in a composition with none. */
    val startConfig: Config?
        get() = configs[start] ?: configs.values.firstOrNull()

    /**
     * [widget] as it stands: where it is one of [singles], the record kept for it, placed where [widget] is placed and
     * holding in each slot [widget] holds what [widget] holds there. Any other widget is itself.
     */
    fun standing(widget: WidgetInstance): WidgetInstance =
        singles[widget.kind]?.let { it.copy(placement = widget.placement, children = it.children + widget.children) } ?: widget
}

/**
 * What a composition asks of the window it is shown in. A zero size says nothing, and the window takes its own.
 *
 * [chrome] and [state] are strings for the reason [FlowSpec.direction] is one: a value a build does not know is read
 * as the plain answer instead of failing the file.
 */
@Serializable
data class Frame(
    val width: Int = 0,
    val height: Int = 0,
    @SerialName("min_width") val minWidth: Int = 0,
    @SerialName("min_height") val minHeight: Int = 0,
    val state: String = NORMAL,
    val chrome: String = SYSTEM,
    val resizable: Boolean = true,
    val title: String = "",
) {
    /** Whether the system draws the window's frame, or widgets do. */
    val systemChrome: Boolean get() = chrome.trim().lowercase() != NONE

    /** Whether the window opens maximised. */
    val opensMaximized: Boolean get() = state.trim().lowercase() == MAXIMIZED

    companion object {
        const val SYSTEM = "system"
        const val NONE = "none"
        const val NORMAL = "normal"
        const val MAXIMIZED = "maximized"
    }
}

/**
 * One thing a composition can show, a screen say: what it is called and how it is laid out, in as many [layouts] as
 * there are window sizes to lay it out for. [title] and [icon] are what a link to it or a crumb of it shows, and an
 * icon a build does not know draws the default one.
 */
@Serializable
data class Config(
    val title: String = "",
    val icon: String = "",
    val layouts: List<ConfigLayout> = emptyList(),
) {
    /**
     * The layout for a composition of [widthDp] by [heightDp]: the widest of those the size holds, the tallest of
     * the widest, and where it holds none the one that asks for least. Null in a config with no layout.
     */
    fun layoutFor(widthDp: Float, heightDp: Float): ConfigLayout? {
        val bySize = compareBy<ConfigLayout>({ it.minWidth }, { it.minHeight })
        return layouts.filter { it.minWidth <= widthDp && it.minHeight <= heightDp }.maxWithOrNull(bySize)
            ?: layouts.minWithOrNull(bySize)
    }
}

/**
 * One way a config is laid out, for a composition of at least [minWidth] by [minHeight] dp. The widgets stand in
 * [root] freely or one after another: no container is needed to hold them.
 */
@Serializable
data class ConfigLayout(
    val id: LayoutId,
    val root: SlotContent = SlotContent(),
    @SerialName("min_width") val minWidth: Float = 0f,
    @SerialName("min_height") val minHeight: Float = 0f,
)
