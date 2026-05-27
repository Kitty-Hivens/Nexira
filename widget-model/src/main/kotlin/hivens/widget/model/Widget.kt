package hivens.widget.model

// Marker annotation scanned by the KSP processor in :widget-processor.
// Apply to top-level @Composable functions with signature
//   fun Name(instance: WidgetInstance)
// Wrong signatures fail the build with a KSP diagnostic.
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
)
