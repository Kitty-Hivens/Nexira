package hivens.widget.processor

import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.Modifier

// Shared rule-set for what a @Widget composable must look like. The KSP
// processor validates Kotlin sources at compile time; Phase E's plugin
// loader will validate java.lang.reflect.Method at runtime. The rules
// are the same: top-level + @Composable + single WidgetInstance param
// + no inline/suspend/extension + non-blank id. Centralising them here
// keeps the two entry points consistent.
internal object WidgetValidator {

    private const val WIDGET_ANNOTATION_FQN = "hivens.widget.model.Widget"
    private const val COMPOSABLE_ANNOTATION_FQN = "androidx.compose.runtime.Composable"
    private const val WIDGET_INSTANCE_FQN = "hivens.widget.model.WidgetInstance"

    // Annotation args extracted from a valid @Widget declaration.
    data class Extracted(
        val id: String,
        val displayName: String,
        val removable: Boolean,
        val slots: List<String>,
    )

    // KSP entry point. Returns the extracted annotation args, or null
    // if validation failed; in the null case, every failure has already
    // been reported through `env.logger.error` and the build will fail.
    fun validate(symbol: KSFunctionDeclaration, env: SymbolProcessorEnvironment): Extracted? {
        val widgetAnnotation = symbol.annotations.firstOrNull {
            it.shortName.asString() == "Widget" &&
                it.annotationType.resolve().declaration.qualifiedName?.asString() == WIDGET_ANNOTATION_FQN
        } ?: run {
            env.logger.error("missing @Widget annotation", symbol)
            return null
        }

        val composable = symbol.annotations.any {
            it.shortName.asString() == "Composable" &&
                it.annotationType.resolve().declaration.qualifiedName?.asString() == COMPOSABLE_ANNOTATION_FQN
        }
        if (!composable) {
            env.logger.error("@Widget functions must also be @Composable", symbol)
            return null
        }

        if (symbol.parentDeclaration != null) {
            env.logger.error("@Widget composables must be top-level", symbol)
            return null
        }

        val params = symbol.parameters
        if (params.size != 1) {
            env.logger.error(
                "@Widget composables must take exactly one parameter (instance: WidgetInstance)",
                symbol,
            )
            return null
        }
        val paramType = params[0].type.resolve().declaration.qualifiedName?.asString()
        if (paramType != WIDGET_INSTANCE_FQN) {
            env.logger.error(
                "@Widget composable parameter must be hivens.widget.model.WidgetInstance, got $paramType",
                symbol,
            )
            return null
        }

        if (Modifier.INLINE in symbol.modifiers || Modifier.SUSPEND in symbol.modifiers) {
            env.logger.error("@Widget composables cannot be inline or suspend", symbol)
            return null
        }
        if (symbol.extensionReceiver != null) {
            env.logger.error("@Widget composables cannot have an extension receiver", symbol)
            return null
        }

        val args = widgetAnnotation.arguments.associate { it.name?.asString() to it.value }
        val id = (args["id"] as? String).orEmpty()
        val displayName = (args["displayName"] as? String).orEmpty()
        val removable = (args["removable"] as? Boolean) ?: true
        // KSP reports Array<String> annotation values as List<*>. Filter
        // defensively; an empty list is the leaf default.
        val rawSlots = args["slots"] as? List<*>
        val slots = rawSlots?.filterIsInstance<String>()?.filter { it.isNotBlank() }.orEmpty()

        if (id.isBlank()) {
            env.logger.error("@Widget id must be non-blank", symbol)
            return null
        }

        return Extracted(
            id = id,
            displayName = displayName,
            removable = removable,
            slots = slots,
        )
    }
}
