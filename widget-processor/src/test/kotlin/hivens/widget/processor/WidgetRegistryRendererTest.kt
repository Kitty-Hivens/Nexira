package hivens.widget.processor

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The registry the whole widget system resolves through. A wrong one still
 * compiles: the slot renderer gets a null descriptor and falls through to the
 * unknown-widget decorator, which draws nothing in production. So these assert
 * the shape of the emitted source, not just that something was emitted.
 */
class WidgetRegistryRendererTest {

    private fun widget(
        id: String,
        displayName: String = "Display",
        removable: Boolean = true,
        slots: List<String> = emptyList(),
        propsClassFqn: String? = null,
        functionFqn: String = "hivens.ui.widgets.Sample",
        takesInstance: Boolean = true,
        surfaceJson: String? = null,
    ) = WidgetModel(
        id, displayName, removable, slots, propsClassFqn, functionFqn, takesInstance,
        surfaceJson = surfaceJson,
    )

    // --- the declared default plane ---

    @Test
    fun `a declared plane is emitted, decoded on first read`() {
        val src = renderRegistry(listOf(widget("home.card", surfaceJson = """{"fill":"base","opacity":0.45}""")))
        assertContains(src, "import hivens.widget.model.SurfaceSpec")
        assertContains(src, "override val defaultSurface: SurfaceSpec? by lazy {")
        // Escaped into a Kotlin literal, not pasted raw: an unescaped quote here
        // would not compile, and the failure would be in generated code.
        assertContains(src, """decodeFromString(SurfaceSpec.serializer(), "{\"fill\":\"base\",\"opacity\":0.45}")""")
    }

    @Test
    fun `a widget without a plane emits neither the import nor the field`() {
        val src = renderRegistry(listOf(widget("home.card")))
        assertFalse("SurfaceSpec" in src, "an unused import warns on a build that declares no plane")
        assertFalse("defaultSurface" in src, src)
    }

    // --- duplicate ids ---

    @Test
    fun `a shared id is reported`() {
        val dupes = duplicateIds(
            listOf(
                widget("home.clock", functionFqn = "a.Clock"),
                widget("home.clock", functionFqn = "b.Clock"),
                widget("home.news", functionFqn = "a.News"),
            ),
        )
        assertEquals(setOf("home.clock"), dupes.keys)
        assertEquals(
            listOf("a.Clock", "b.Clock"),
            dupes.getValue("home.clock").map { it.functionFqn },
            "both declarations are carried so each gets a diagnostic",
        )
    }

    @Test
    fun `distinct ids are not reported`() {
        assertTrue(duplicateIds(listOf(widget("a"), widget("b"), widget("c"))).isEmpty())
    }

    @Test
    fun `an empty registry is not a duplicate`() {
        assertTrue(duplicateIds(emptyList()).isEmpty())
    }

    // --- rendered shape ---

    @Test
    fun `every widget gets an entry keyed by its id`() {
        val src = renderRegistry(listOf(widget("home.clock"), widget("home.news")))
        assertContains(src, "put(WidgetKind(\"home.clock\")")
        assertContains(src, "put(WidgetKind(\"home.news\")")
        assertEquals(2, Regex("""^\s{8}put\(""", RegexOption.MULTILINE).findAll(src).count())
    }

    @Test
    fun `the render call targets the annotated function`() {
        val src = renderRegistry(listOf(widget("x", functionFqn = "hivens.ui.widgets.bgsettings.BgTintWidget")))
        assertContains(src, "hivens.ui.widgets.bgsettings.BgTintWidget(instance)")
    }

    @Test
    fun `a widget that takes no instance is called with no argument`() {
        // Passing one to a `fun Name()` would not compile, and the whole point of
        // allowing the shorter signature is that the author never writes a
        // parameter they cannot read.
        val src = renderRegistry(
            listOf(widget("x", functionFqn = "hivens.ui.widgets.bgsettings.BgTintWidget", takesInstance = false)),
        )
        assertContains(src, "hivens.ui.widgets.bgsettings.BgTintWidget()")
        assertFalse(src.contains("BgTintWidget(instance)"))
    }

    @Test
    fun `the descriptor still receives the instance either way`() {
        // Only the call inside Render changes. The override's own signature is
        // the registry's contract with the slot renderer and does not move.
        val bare = renderRegistry(listOf(widget("x", takesInstance = false)))
        val full = renderRegistry(listOf(widget("x", takesInstance = true)))
        val signature = "@Composable override fun Render(instance: WidgetInstance) {"
        assertContains(bare, signature)
        assertContains(full, signature)
    }

    @Test
    fun `both signatures coexist in one registry`() {
        val src = renderRegistry(
            listOf(
                widget("a", functionFqn = "p.Bare", takesInstance = false),
                widget("b", functionFqn = "p.Full", propsClassFqn = "p.Props"),
            ),
        )
        assertContains(src, "p.Bare()")
        assertContains(src, "p.Full(instance)")
    }

    @Test
    fun `removable is carried through verbatim`() {
        assertContains(renderRegistry(listOf(widget("x", removable = false))), "override val removable: Boolean = false")
        assertContains(renderRegistry(listOf(widget("x", removable = true))), "override val removable: Boolean = true")
    }

    @Test
    fun `slots render as SlotId literals, or an empty list`() {
        assertContains(
            renderRegistry(listOf(widget("x", slots = listOf("left", "right")))),
            "listOf(SlotId(\"left\"), SlotId(\"right\"))",
        )
        assertContains(
            renderRegistry(listOf(widget("x", slots = emptyList()))),
            "override val slots: List<SlotId> = emptyList()",
        )
    }

    // --- props branch ---

    @Test
    fun `a propless registry emits no serialization imports`() {
        val src = renderRegistry(listOf(widget("x")))
        assertFalse(src.contains("kotlinx.serialization"), "unused imports would warn on a props-free build")
        assertFalse(src.contains("propsJson"))
        assertFalse(src.contains("propsSerializer"))
    }

    @Test
    fun `a props class contributes its serializer and default baseline`() {
        val src = renderRegistry(listOf(widget("x", propsClassFqn = "hivens.ui.widgets.ClockProps")))
        assertContains(src, "import kotlinx.serialization.KSerializer")
        // Lazy: the palette lists a kind without needing its serializer, and building
        // one loads the props class. A launcher pays for the widgets it draws.
        assertContains(src, "override val propsSerializer: KSerializer<*>? by lazy { hivens.ui.widgets.ClockProps.serializer() }")
        assertContains(src, "override val defaultPropsJson: JsonObject by lazy {")
        assertContains(src, "hivens.ui.widgets.ClockProps.serializer(), hivens.ui.widgets.ClockProps()")
        // encodeDefaults is what makes the baseline carry every field rather
        // than only the ones that differ from their defaults.
        assertContains(src, "encodeDefaults = true")
    }

    @Test
    fun `one props class among many pulls the imports in once`() {
        val src = renderRegistry(
            listOf(widget("a"), widget("b", propsClassFqn = "p.Props"), widget("c")),
        )
        assertEquals(1, Regex("import kotlinx\\.serialization\\.KSerializer").findAll(src).count())
        assertEquals(1, Regex("private val propsJson").findAll(src).count())
    }

    // --- escaping ---

    @Test
    fun `quotes and backslashes in an id survive as literals`() {
        val src = renderRegistry(listOf(widget("""we"ird\id""", displayName = """say "hi"""")))
        assertContains(src, """WidgetKind("we\"ird\\id")""")
        assertContains(src, """override val displayName: String = "say \"hi\""""")
    }

    // --- file frame ---

    @Test
    fun `the file declares the generated package and the registry object`() {
        val src = renderRegistry(listOf(widget("x")))
        assertContains(src, "package $DEFAULT_GENERATED_PACKAGE")
        assertContains(src, "object $DEFAULT_GENERATED_NAME : WidgetRegistry {")
        assertContains(src, "override fun all(): Map<WidgetKind, WidgetDescriptor> = map")
        assertContains(src, "override fun get(kind: WidgetKind): WidgetDescriptor? = map[kind]")
    }

    @Test
    fun `an empty registry still renders a usable object`() {
        val src = renderRegistry(emptyList())
        assertContains(src, "object $DEFAULT_GENERATED_NAME : WidgetRegistry {")
        assertContains(src, "buildMap {")
        assertFalse(src.contains("put("), "nothing to register")
    }

    @Test
    fun `rendering is deterministic for a fixed order`() {
        val widgets = listOf(widget("a"), widget("b", propsClassFqn = "p.P"))
        assertEquals(renderRegistry(widgets), renderRegistry(widgets))
    }

    @Test
    fun `a module can emit its own registry object`() {
        // Two modules both running the processor would otherwise produce the
        // same fully-qualified object; whichever lost the classpath race would
        // take its widgets with it and say nothing.
        val src = renderRegistry(
            listOf(widget("theme.swatch")),
            packageName = "hivens.theme.generated",
            objectName = "ThemeWidgetRegistry",
        )
        assertContains(src, "package hivens.theme.generated")
        assertContains(src, "object ThemeWidgetRegistry : WidgetRegistry {")
    }

    @Test
    fun `the default identity is unchanged`() {
        // The existing module's output has to stay byte-identical: this is a new
        // capability, not a migration.
        val src = renderRegistry(listOf(widget("home.new.clock")))
        assertContains(src, "package hivens.widget.generated")
        assertContains(src, "object GeneratedWidgetRegistry : WidgetRegistry {")
    }
}
