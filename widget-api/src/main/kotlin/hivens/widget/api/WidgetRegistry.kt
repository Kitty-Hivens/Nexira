package hivens.widget.api

import androidx.compose.runtime.Composable
import hivens.widget.model.SlotId
import hivens.widget.model.SurfaceSpec
import hivens.widget.model.WidgetInstance
import hivens.widget.model.WidgetKind
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject

interface WidgetDescriptor {
    val kind: WidgetKind
    val displayName: String

    // false hides the remove affordance in the editor. Drives the
    // safety check on nav rail / auth panel widgets.
    val removable: Boolean

    // True when the widget paints its own plane, so nothing should paint one for
    // it and the editor should not offer to. See [hivens.widget.model.Widget].
    val drawsOwnSurface: Boolean
        get() = false

    // Slot ids this widget exposes for nested sub-widgets. Empty for
    // leaves. Populated by the KSP processor from @Widget(slots = ...).
    // Editor uses this to drive drop-target discovery inside container
    // chromes; the layout-graph mutator uses it (indirectly, via the
    // structural keys in WidgetInstance.children) to validate moves.
    val slots: List<SlotId>
        get() = emptyList()

    // The plane this widget sits on when nothing has said otherwise. Populated by
    // the KSP processor from @Widget(surface = ...); null means the widget draws
    // no plane of its own. See [resolveSurface] for how it meets an instance's.
    val defaultSurface: SurfaceSpec?
        get() = null

    // Typed-props support, populated by the KSP processor from
    // @Widget(propsClass = ...). A null serializer means the widget is
    // propless (the default) -- the editor shows no prop affordance.
    // When non-null, the editor builds its form from
    // propsSerializer.descriptor (element names / kinds / @SerialInfo
    // annotations) and reads current values from defaultPropsJson
    // overlaid with the instance's stored props. Widgets read typed
    // values via WidgetInstance.rememberProps.
    val propsSerializer: KSerializer<*>?
        get() = null

    val defaultPropsJson: JsonObject
        get() = JsonObject(emptyMap())

    // Service contracts this widget claims, populated by the KSP processor from
    // @ProvidesService / @InjectService. Names, not classes: the registry stays
    // reflection-free, and the only consumer is a comparison between the two
    // sets. Registration itself is still the widget's own provideService(...)
    // call -- the annotations describe intent, and recording them here is what
    // lets anything check that the intent is satisfiable.
    val provides: Set<String>
        get() = emptySet()

    val injects: Set<String>
        get() = emptySet()

    @Composable
    fun Render(instance: WidgetInstance)
}

interface WidgetRegistry {
    fun all(): Map<WidgetKind, WidgetDescriptor>
    operator fun get(kind: WidgetKind): WidgetDescriptor?
}

/**
 * The plane an instance draws: its own if it has one, otherwise its widget's
 * declaration.
 *
 * The same order the props take -- declared defaults under the instance's
 * overrides -- and for the same reason. Without it a widget's plane depended on
 * how it reached the layout: seeded from the declaration when dropped from the
 * palette, absent when shipped in the bundled layout, so every bundled entry had
 * to repeat JSON its widget already carried and the two drifted apart the first
 * time one of them changed.
 *
 * A widget that wants no plane at all names an opacity of zero. That is a value
 * like any other, which "no surface record" is not: absence has to mean "nothing
 * said" for the declaration to be reachable through it.
 */
fun WidgetDescriptor.resolveSurface(instance: WidgetInstance): SurfaceSpec? =
    instance.surface ?: defaultSurface
