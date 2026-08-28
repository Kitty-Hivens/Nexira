package hivens.widget.processor

import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSFunctionDeclaration

private const val WIDGET_ANNOTATION_FQN = "hivens.widget.model.Widget"

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
            val extracted = WidgetValidator.validate(symbol, env) ?: return@mapNotNull null
            val packageName = symbol.packageName.asString()
            val funcName = symbol.simpleName.asString()
            WidgetEntry(
                model = WidgetModel(
                    id = extracted.id,
                    displayName = extracted.displayName.ifBlank { funcName },
                    removable = extracted.removable,
                    slots = extracted.slots,
                    propsClassFqn = extracted.propsClassFqn,
                    functionFqn = if (packageName.isEmpty()) funcName else "$packageName.$funcName",
                    takesInstance = extracted.takesInstance,
                    provides = extracted.provides,
                    injects = extracted.injects,
                ),
                containingFile = symbol.containingFile,
                symbol = symbol,
            )
        }

        if (widgets.isEmpty() && symbols.isNotEmpty()) {
            // Some symbols matched but all failed validation; emit nothing
            // so the build fails on diagnostics rather than producing a
            // stub registry that silently drops widgets.
            return emptyList()
        }

        // Cross-symbol id uniqueness, against Widget.kt's "id MUST be unique
        // across the whole runtime". See [duplicateIds] for what a shared id
        // would otherwise cost.
        val collisions = duplicateIds(widgets.map { it.model })
        if (collisions.isNotEmpty()) {
            val byId = widgets.groupBy { it.model.id }
            collisions.keys.forEach { id ->
                val entries = byId.getValue(id)
                val others = entries.joinToString { it.model.functionFqn }
                entries.forEach { entry ->
                    env.logger.error(
                        "Duplicate @Widget id '$id' -- declared by $others. " +
                            "Widget ids MUST be unique across the runtime.",
                        entry.symbol,
                    )
                }
            }
            // Emit nothing so the build fails on the diagnostics above.
            return emptyList()
        }

        // A contract read by someone and offered by no one. Reported as a
        // warning rather than an error: a plugin-supplied provider is the point
        // of the SPI, so a build with only the consumer in it is a legitimate
        // state -- what is not legitimate is nobody noticing.
        val byWidget = widgets.associateBy { it.model }
        injectorsWithoutProvider(widgets.map { it.model }).forEach { (model, unmet) ->
            env.logger.warn(
                "@Widget '${model.id}' injects ${unmet.joinToString()} but no widget in this build " +
                    "provides it -- the registry will hand it null on every frame.",
                byWidget[model]?.symbol,
            )
        }

        emitGeneratedFile(widgets)
        emitted = true
        return emptyList()
    }

    // Overridable so a second module carrying widgets emits a distinct object:
    // two modules on one classpath cannot both own hivens.widget.generated.
    // GeneratedWidgetRegistry, and the one that lost would take its widgets with
    // it silently. Defaults keep the existing module's output byte-identical.
    private val registryPackage = env.options["widgetRegistryPackage"] ?: DEFAULT_GENERATED_PACKAGE
    private val registryName = env.options["widgetRegistryName"] ?: DEFAULT_GENERATED_NAME

    private fun emitGeneratedFile(widgets: List<WidgetEntry>) {
        val sourceFiles = widgets.mapNotNull { it.containingFile }.toTypedArray()
        // aggregating = true: any change to ANY @Widget source invalidates
        // the generated registry; KSP needs this for correct incremental
        // behavior when widgets are added or renamed.
        val deps = Dependencies(aggregating = true, sources = sourceFiles)
        env.codeGenerator.createNewFile(
            dependencies = deps,
            packageName = registryPackage,
            fileName = registryName,
            extensionName = "kt",
        ).use { stream ->
            stream.writer(Charsets.UTF_8).use { writer ->
                writer.write(renderRegistry(widgets.map { it.model }, registryPackage, registryName))
            }
        }

        // Discovery for a module loaded from a jar at runtime. Naming the
        // provider here rather than agreeing a class name with the loader means
        // a module author picks whatever names they like and still gets found;
        // the only thing both sides have to agree on is the interface.
        env.codeGenerator.createNewFileByPath(
            dependencies = deps,
            path = REGISTRY_SERVICE_FILE,
            extensionName = "",
        ).use { stream ->
            stream.writer(Charsets.UTF_8).use { writer ->
                writer.write(providerFqn(registryPackage, registryName))
                writer.write("\n")
            }
        }
    }
}

private data class WidgetEntry(
    val model: WidgetModel,
    val containingFile: KSFile?,
    // Carried only so a duplicate-id diagnostic can point at the declaration.
    val symbol: KSFunctionDeclaration,
)

class WidgetRegistryProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        WidgetRegistryProcessor(environment)
}
