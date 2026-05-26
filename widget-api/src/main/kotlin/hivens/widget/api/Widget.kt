package hivens.widget.api

// Marker annotation scanned by the KSP processor in :widget-processor.
// Apply to top-level @Composable functions with signature
//   fun Name(instance: WidgetInstance)
// Wrong signatures fail the build with a KSP diagnostic.
//
// id MUST be unique across the whole runtime. Convention:
//   "<surface>.<role>"        for kernel widgets ("home.classic.header")
//   "<plugin-id>.<role>"      for plugin-contributed widgets (Phase 3)
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class Widget(
    val id: String,
    val displayName: String = "",
)
