package hivens.widget.processor

import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSType
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

    private const val SERIALIZABLE_ANNOTATION_FQN = "kotlinx.serialization.Serializable"

    // Annotation args extracted from a valid @Widget declaration.
    data class Extracted(
        val id: String,
        val displayName: String,
        val removable: Boolean,
        val slots: List<String>,
        // FQN of the @Serializable props class, or null for Unit::class
        // (a propless widget).
        val propsClassFqn: String?,
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
        // KSP reports Array<String> annotation values as List<*>.
        val rawSlots = (args["slots"] as? List<*>).orEmpty().filterIsInstance<String>()

        if (id.isBlank()) {
            env.logger.error("@Widget id must be non-blank", symbol)
            return null
        }

        // Slot ids must be non-blank, free of whitespace (the editor
        // uses them as JSON keys and CompositionLocal-resolved drop
        // target keys; a slot id with a space deserializes fine but
        // breaks any author who tries to write it back in source), and
        // unique within a single descriptor. Reject loudly so a typo
        // surfaces at build time, not as silent UI breakage.
        val sanitized = mutableListOf<String>()
        val seen = HashSet<String>()
        for (raw in rawSlots) {
            if (raw.isBlank()) {
                env.logger.error("@Widget slot id must be non-blank", symbol)
                return null
            }
            if (raw.any { it.isWhitespace() }) {
                env.logger.error("@Widget slot id '$raw' must not contain whitespace", symbol)
                return null
            }
            if (!seen.add(raw)) {
                env.logger.error("@Widget slots contain duplicate id '$raw'", symbol)
                return null
            }
            sanitized.add(raw)
        }

        // propsClass: a KClass<*> annotation arg arrives as a KSType.
        // Unit::class (the default) means "no props". Anything else must
        // be @Serializable with an all-default primary constructor, so
        // the generated registry can build the zero-arg default-props
        // baseline and the editor can read its serializer descriptor.
        val propsType = args["propsClass"] as? KSType
        val propsDecl = propsType?.declaration
        val propsClassFqn = propsDecl?.qualifiedName?.asString()?.takeIf { it != "kotlin.Unit" }
        if (propsClassFqn != null) {
            val isSerializable = propsDecl!!.annotations.any {
                it.shortName.asString() == "Serializable" &&
                    it.annotationType.resolve().declaration.qualifiedName?.asString() ==
                        SERIALIZABLE_ANNOTATION_FQN
            }
            if (!isSerializable) {
                env.logger.error(
                    "@Widget propsClass '$propsClassFqn' must be annotated @$SERIALIZABLE_ANNOTATION_FQN",
                    symbol,
                )
                return null
            }
            val primaryCtor = (propsDecl as? KSClassDeclaration)?.primaryConstructor
            if (primaryCtor != null && primaryCtor.parameters.any { !it.hasDefault }) {
                env.logger.error(
                    "@Widget propsClass '$propsClassFqn' must give every property a default " +
                        "-- the registry needs a zero-arg baseline",
                    symbol,
                )
                return null
            }
        }

        return Extracted(
            id = id,
            displayName = displayName,
            removable = removable,
            slots = sanitized,
            propsClassFqn = propsClassFqn,
        )
    }
}
