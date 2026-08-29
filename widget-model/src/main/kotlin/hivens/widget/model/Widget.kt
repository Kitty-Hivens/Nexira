package hivens.widget.model

import kotlin.reflect.KClass

// Marker annotation scanned by the KSP processor in :widget-processor.
// Apply to top-level @Composable functions with signature
//   fun Name(instance: WidgetInstance)   -- reads props or per-instance state
//   fun Name()                           -- reads neither
// The instance is what carries props and the instance id, so a widget that
// declares [propsClass] or keeps state under its instance id has to take it
// and the build says so; one that draws from its surface context alone takes
// nothing. Any other signature fails the build with a KSP diagnostic.
//
// id MUST be unique across the whole runtime. Convention:
//   "<surface>.<role>"        for kernel widgets ("home.classic.header")
//   "<plugin-id>.<role>"      for plugin-contributed widgets (Phase 3)
//
// Lives in :widget-model rather than :widget-api so it carries no
// Compose dependency. The @Composable requirement is enforced by the
// processor, not the annotation's classpath.
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class Widget(
    val id: String,
    val displayName: String = "",
    // false hides the remove affordance on this widget in the editor.
    // Use for widgets whose removal would brick a surface (nav rail
    // buttons, the auth panel) -- the user can still rearrange but
    // cannot delete. Defaults true; opt-out is explicit.
    val removable: Boolean = true,
    // Slot ids this widget exposes as drop targets for its own
    // sub-widgets (container widgets). Empty for leaves. Walked by
    // the editor's drop hit-test and by the KSP processor to validate
    // that container.children references only declared slots.
    val slots: Array<String> = [],
    // The @Serializable data class holding this widget's tunable props,
    // or Unit::class (the default) for a propless widget. Every field
    // must have a default so the KSP-generated registry can construct
    // an instance for the default-props baseline. The processor checks
    // the class carries @kotlinx.serialization.Serializable and emits
    // its serializer into the descriptor; the widget body reads typed
    // values via instance.rememberProps<T>(), the editor builds its
    // form from the serializer's descriptor.
    val propsClass: KClass<*> = Unit::class,
    // The plane this widget sits on when nothing has said otherwise, as a
    // [SurfaceSpec] in the same JSON the layout file carries. Blank (the default)
    // means no plane: the widget draws its content and nothing behind it.
    //
    // A string rather than an object because an annotation cannot hold one, and the
    // layout's own grammar rather than a second set of arguments because there is
    // then one thing to learn and one parser to trust. The processor decodes it at
    // build time and fails the build on a malformed value, which is the whole reason
    // it lives here instead of in a table of kinds somewhere central: a widget
    // declares its own plane, beside itself, and a mistake is caught before it ships.
    val surface: String = "",
)
