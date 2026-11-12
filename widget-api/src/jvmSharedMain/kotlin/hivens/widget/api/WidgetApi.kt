package hivens.widget.api

/**
 * The widget ABI a build speaks.
 *
 * A widget compiled into a jar carries calls into compose-runtime with the
 * calling convention its compiler generated: a Composer parameter, change flags,
 * group keys. None of that is stable across Compose versions, so a module built
 * against one launcher can fail to link against the next, or -- worse -- link and
 * misbehave. There is no compatibility to be had here, only an honest refusal.
 *
 * So a loadable module declares the version it was built against and the loader
 * refuses anything else, saying so. Bump this whenever the kernel's shape or the
 * Compose runtime under it changes in a way a compiled module would notice: the
 * ABI dumps of :widget-api and :widget-model change with every such change, and a
 * changed dump is the cue. The module builds stamp their manifests from the
 * `nexira.widgetApi` Gradle property, which a test holds equal to this.
 *
 * 2: source and command keys gained a level, so their constructors changed.
 */
object WidgetApi {
    const val VERSION: Int = 2

    /** Jar manifest attribute carrying [VERSION]. */
    const val MANIFEST_VERSION = "Nexira-Widget-Api"

    /** Jar manifest attribute carrying the module's stable id. */
    const val MANIFEST_ID = "Nexira-Module-Id"

    /** Jar manifest attribute carrying the human-readable name. Optional. */
    const val MANIFEST_NAME = "Nexira-Module-Name"
}
