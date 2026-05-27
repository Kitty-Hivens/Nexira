package hivens.widget.processor

import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.Modifier

private const val WIDGET_ANNOTATION_FQN = "hivens.widget.model.Widget"
private const val COMPOSABLE_ANNOTATION_FQN = "androidx.compose.runtime.Composable"
private const val WIDGET_INSTANCE_FQN = "hivens.widget.model.WidgetInstance"

private const val GENERATED_PACKAGE = "hivens.widget.generated"
private const val GENERATED_FILE_NAME = "GeneratedWidgetRegistry"

class WidgetRegistryProcessor(
    private val env: SymbolProcessorEnvironment,
) : SymbolProcessor {

    // KSP multi-round contract: process() can be called more than once
    // until no new symbols are deferred. We collect all valid widgets
    // across rounds and emit a single file on first round only -- KSP
    // will short-circuit subsequent rounds because nothing was deferred.
    private var emitted = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (emitted) return emptyList()

        val symbols = resolver
            .getSymbolsWithAnnotation(WIDGET_ANNOTATION_FQN)
            .toList()

        val widgets = symbols.mapNotNull { symbol ->
            if (symbol !is KSFunctionDeclaration) {
                env.logger.error("@Widget is only valid on functions", symbol)
                return@mapNotNull null
            }
            validateAndExtract(symbol)
        }

        if (widgets.isEmpty() && symbols.isNotEmpty()) {
            // Some symbols matched but all failed validation; emit nothing
            // so the build fails on diagnostics rather than producing a
            // stub registry that silently drops widgets.
            return emptyList()
        }

        emitGeneratedFile(widgets)
        emitted = true
        return emptyList()
    }

    private fun validateAndExtract(symbol: KSFunctionDeclaration): WidgetEntry? {
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

        // Top-level only -- anonymous + class-member composables would
        // require a receiver to invoke from the generated registry.
        if (symbol.parentDeclaration != null) {
            env.logger.error("@Widget composables must be top-level", symbol)
            return null
        }

        val params = symbol.parameters
        if (params.size != 1) {
            env.logger.error("@Widget composables must take exactly one parameter (instance: WidgetInstance)", symbol)
            return null
        }
        val paramType = params[0].type.resolve().declaration.qualifiedName?.asString()
        if (paramType != WIDGET_INSTANCE_FQN) {
            env.logger.error("@Widget composable parameter must be hivens.widget.model.WidgetInstance, got $paramType", symbol)
            return null
        }

        // Inline / suspend / extension / receiver composables make the
        // generated indirection awkward; reject up front.
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
        if (id.isBlank()) {
            env.logger.error("@Widget id must be non-blank", symbol)
            return null
        }

        val packageName = symbol.packageName.asString()
        val funcName = symbol.simpleName.asString()
        return WidgetEntry(
            id = id,
            displayName = displayName.ifBlank { funcName },
            removable = removable,
            functionFqn = if (packageName.isEmpty()) funcName else "$packageName.$funcName",
            containingFile = symbol.containingFile,
        )
    }

    private fun emitGeneratedFile(widgets: List<WidgetEntry>) {
        val sourceFiles = widgets.mapNotNull { it.containingFile }.toTypedArray()
        // aggregating = true: any change to ANY @Widget source invalidates
        // the generated registry; KSP needs this for correct incremental
        // behavior when widgets are added or renamed.
        val deps = Dependencies(aggregating = true, sources = sourceFiles)
        env.codeGenerator.createNewFile(
            dependencies = deps,
            packageName = GENERATED_PACKAGE,
            fileName = GENERATED_FILE_NAME,
            extensionName = "kt",
        ).use { stream ->
            stream.writer(Charsets.UTF_8).use { writer ->
                writer.write(renderFile(widgets))
            }
        }
    }

    private fun renderFile(widgets: List<WidgetEntry>): String = buildString {
        appendLine("// Generated by hivens.widget.processor.WidgetRegistryProcessor.")
        appendLine("// Do not edit -- changes are overwritten on the next build.")
        appendLine("package $GENERATED_PACKAGE")
        appendLine()
        appendLine("import androidx.compose.runtime.Composable")
        appendLine("import hivens.widget.api.WidgetDescriptor")
        appendLine("import hivens.widget.api.WidgetRegistry")
        appendLine("import hivens.widget.model.WidgetInstance")
        appendLine("import hivens.widget.model.WidgetKind")
        appendLine()
        appendLine("object $GENERATED_FILE_NAME : WidgetRegistry {")
        appendLine("    private val map: Map<WidgetKind, WidgetDescriptor> = buildMap {")
        widgets.forEach { entry ->
            val kindLiteral = "WidgetKind(\"${entry.id.kotlinEscape()}\")"
            appendLine("        put($kindLiteral, object : WidgetDescriptor {")
            appendLine("            override val kind: WidgetKind = $kindLiteral")
            appendLine("            override val displayName: String = \"${entry.displayName.kotlinEscape()}\"")
            appendLine("            override val removable: Boolean = ${entry.removable}")
            appendLine("            @Composable override fun Render(instance: WidgetInstance) {")
            appendLine("                ${entry.functionFqn}(instance)")
            appendLine("            }")
            appendLine("        })")
        }
        appendLine("    }")
        appendLine()
        appendLine("    override fun all(): Map<WidgetKind, WidgetDescriptor> = map")
        appendLine("    override fun get(kind: WidgetKind): WidgetDescriptor? = map[kind]")
        appendLine("}")
    }

    private fun String.kotlinEscape(): String =
        replace("\\", "\\\\").replace("\"", "\\\"")
}

private data class WidgetEntry(
    val id: String,
    val displayName: String,
    val removable: Boolean,
    val functionFqn: String,
    val containingFile: com.google.devtools.ksp.symbol.KSFile?,
)

class WidgetRegistryProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        WidgetRegistryProcessor(environment)
}
