package hivens.pages.api

import hivens.widget.model.Composition
import hivens.widget.model.CompositionName
import hivens.widget.model.Document
import hivens.widget.model.SlotContent
import hivens.widget.model.SlotId
import hivens.widget.model.WidgetInstance
import hivens.widget.model.flatMapWidgets
import hivens.widget.model.widgets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

// What a person made of the pages lives in the host's own record: its props say which screens they made and what they
// called the others, and its slots hold what each screen draws. These read and change that record, for the editor and
// for any module that makes screens of its own.

private val HostJson = Json { ignoreUnknownKeys = true; encodeDefaults = false }

/** The pages host the composition holds, the first where it holds several, or null for none. */
fun Composition.pagesHost(): WidgetInstance? = widgets().firstOrNull { it.kind == PagesHost.KIND }

/** What this host keeps of its own, or the defaults where its record cannot be read: the file is somebody's to edit. */
fun WidgetInstance.pagesHostProps(): PagesHostProps =
    runCatching { HostJson.decodeFromJsonElement(PagesHostProps.serializer(), props) }.getOrNull() ?: PagesHostProps()

/** This host keeping [props], what equals a default left out of the record. */
fun WidgetInstance.withPagesHostProps(props: PagesHostProps): WidgetInstance {
    val encoded = HostJson.encodeToJsonElement(PagesHostProps.serializer(), props).jsonObject
    return if (encoded == this.props) this else copy(props = JsonObject(encoded))
}

/** What this host holds for [screen], or null where it holds nothing and the screen draws what its module brought. */
fun WidgetInstance.screenContent(screen: ScreenId): SlotContent? = children[SlotId(screen.value)]

/**
 * The document with the pages host of [composition] as [change] makes it, kept once or standing where it stands.
 * Identity where the composition holds no host or [change] changes nothing.
 */
fun Document.withPagesHost(composition: CompositionName, change: (WidgetInstance) -> WidgetInstance): Document {
    val current = compositions[composition] ?: return this
    val host = current.pagesHost() ?: return this
    val single = current.singles.entries.firstOrNull { it.value.instanceId == host.instanceId }
    if (single != null) {
        val next = change(single.value)
        if (next == single.value) return this
        return copy(compositions = compositions + (composition to current.copy(singles = current.singles + (single.key to next))))
    }
    return flatMapWidgets(composition) { w -> listOf(if (w.instanceId == host.instanceId) change(w) else w) }
}

/** The document with [screen] made in the host of [composition], holding [content], in place of one under its id. */
fun Document.withMadeScreen(composition: CompositionName, screen: MadeScreen, content: SlotContent = SlotContent()): Document =
    withPagesHost(composition) { host ->
        val props = host.pagesHostProps()
        val made = props.made.filter { it.id != screen.id } + screen
        host.withPagesHostProps(props.copy(made = made)).copy(children = host.children + (SlotId(screen.id) to content))
    }

/**
 * The document with the screen [id] called [title] and wearing [icon] in the host of [composition]. Null leaves that
 * half as it is, and blank is what the screen has without the person. A screen they made keeps it in its own entry,
 * one a module brought in [PagesHostProps.named], which drops it again once both halves are blank.
 */
fun Document.withScreenNamed(composition: CompositionName, id: ScreenId, title: String? = null, icon: String? = null): Document =
    withPagesHost(composition) { host ->
        val props = host.pagesHostProps()
        fun MadeScreen.changed() = copy(title = title ?: this.title, icon = icon ?: this.icon)
        val next = if (props.made.any { it.id == id.value }) {
            props.copy(made = props.made.map { if (it.id == id.value) it.changed() else it })
        } else {
            val named = (props.named.firstOrNull { it.id == id.value } ?: MadeScreen(id.value)).changed()
            val others = props.named.filter { it.id != id.value }
            props.copy(named = if (named.title.isBlank() && named.icon.isBlank()) others else others + named)
        }
        host.withPagesHostProps(next)
    }

/** The document without the screen [id] made in the host of [composition], and without what it held. */
fun Document.withoutMadeScreen(composition: CompositionName, id: ScreenId): Document =
    withPagesHost(composition) { host ->
        val props = host.pagesHostProps()
        host.withPagesHostProps(props.copy(made = props.made.filter { it.id != id.value })).copy(children = host.children - SlotId(id.value))
    }

/**
 * The document with the host of [composition] holding [content] for the screen [id], or holding nothing for it where
 * [content] is null, so a screen a module brought draws what the module brought again.
 */
fun Document.withScreenContent(composition: CompositionName, id: ScreenId, content: SlotContent?): Document =
    withPagesHost(composition) { host ->
        val slot = SlotId(id.value)
        host.copy(children = if (content == null) host.children - slot else host.children + (slot to content))
    }
