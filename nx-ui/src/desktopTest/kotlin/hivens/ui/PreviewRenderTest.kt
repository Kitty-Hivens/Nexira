package hivens.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composer
import androidx.compose.runtime.currentComposer
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import hivens.ui.icons.NxIcon
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.IRect
import org.jetbrains.skia.Image
import java.lang.classfile.Attributes
import java.lang.classfile.ClassFile
import java.lang.reflect.InvocationTargetException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Draws every `@Preview` in the module off-screen and fails on any that throws or lays out
 * to nothing, so a preview the IDE would show as a broken frame breaks the build instead.
 *
 * The annotation is kept in the class file but not at run time, so reflection cannot see
 * it. The compiled classes are read with the class-file API instead, which finds exactly the
 * functions the IDE finds. Each one is a private composable with no parameters, so it is
 * called through reflection with the composer of the scene it is drawn in.
 *
 * With `-Dnexira.probe.previews=<dir>` every frame is also written there as a PNG named after
 * its function, for looking at the whole library at once. Without it nothing is written.
 */
class PreviewRenderTest {

    @Test
    fun `every preview renders`() {
        val previews = findPreviews()
        assertTrue(previews.isNotEmpty(), "no @Preview function was found under ${classesRoot()}")
        val out = System.getProperty(OUT_PROPERTY)?.let { Paths.get(it) }?.also { Files.createDirectories(it) }
        val failures = previews.mapNotNull { preview ->
            runCatching { render(preview, out) }.exceptionOrNull()?.let { "${preview.label}: ${it.describe()}" }
        }
        println("PreviewRenderTest: ${previews.size} previews, ${failures.size} failed" + (out?.let { ", frames in $it" } ?: ""))
        if (failures.isNotEmpty()) fail("${failures.size} of ${previews.size} previews did not render:\n" + failures.joinToString("\n"))
    }

    private class PreviewFunction(val owner: String, val name: String) {
        val label: String get() = "${owner.substringAfterLast('.')}.$name"
    }

    /** Where this module's own compiled classes are, a directory under Gradle and possibly a jar elsewhere. */
    private fun classesRoot(): Path = Paths.get(NxIcon::class.java.protectionDomain.codeSource.location.toURI())

    private fun findPreviews(): List<PreviewFunction> {
        val root = classesRoot()
        return if (root.isDirectory()) {
            scan(root)
        } else {
            FileSystems.newFileSystem(root).use { jar -> scan(jar.getPath("/")) }
        }
    }

    private fun scan(root: Path): List<PreviewFunction> =
        Files.walk(root).use { paths -> paths.filter { it.extension == "class" }.toList() }
            .flatMap { file -> previewsIn(file.readBytes()) }
            .sortedBy { it.label }

    private fun previewsIn(bytes: ByteArray): List<PreviewFunction> {
        val model = ClassFile.of().parse(bytes)
        val owner = model.thisClass().asInternalName().replace('/', '.')
        return model.methods().mapNotNull { method ->
            val annotations = method.findAttribute(Attributes.runtimeInvisibleAnnotations()).orElse(null)
                ?.annotations().orEmpty()
            if (annotations.none { it.classSymbol().descriptorString() in PREVIEW_DESCRIPTORS }) return@mapNotNull null
            val name = method.methodName().stringValue()
            val descriptor = method.methodType().stringValue()
            check(descriptor == COMPOSABLE_NO_ARGS) {
                "$owner.$name is a @Preview with parameters ($descriptor): only a preview that takes none can be drawn here"
            }
            PreviewFunction(owner, name)
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    private fun render(preview: PreviewFunction, out: Path?) {
        val method = Class.forName(preview.owner).getDeclaredMethod(preview.name, Composer::class.java, Int::class.javaPrimitiveType)
        method.isAccessible = true
        val measured = SizeHolder()
        val scene = ImageComposeScene(width = CANVAS_PX, height = CANVAS_PX, density = Density(DENSITY)) {
            Measured(measured) {
                val composer = currentComposer
                try {
                    method.invoke(null, composer, 0)
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            }
        }
        try {
            // Enough frames for effects to run and for an entrance to settle.
            var frame: Image = scene.render(0L)
            for (at in FRAMES_MS) frame = scene.render(at * 1_000_000L)
            val size = measured.size
            check(size.width > 0 && size.height > 0) { "it laid out to $size" }
            if (out != null) {
                val bitmap = Bitmap.makeFromImage(frame)
                val crop = Bitmap()
                bitmap.extractSubset(crop, IRect.makeXYWH(0, 0, size.width.coerceAtMost(CANVAS_PX), size.height.coerceAtMost(CANVAS_PX)))
                val png = Image.makeFromBitmap(crop).encodeToData(EncodedImageFormat.PNG) ?: error("PNG encode failed")
                Files.write(out.resolve("${preview.name}.png"), png.bytes)
            }
        } finally {
            scene.close()
        }
    }

    private class SizeHolder {
        var size: IntSize = IntSize.Zero
    }

    /** Lays [content] out at its own size inside the canvas and notes that size, which is what the frame is cropped to. */
    @Composable
    private fun Measured(holder: SizeHolder, content: @Composable () -> Unit) {
        Layout(content) { measurables, constraints ->
            val loose = Constraints(maxWidth = constraints.maxWidth, maxHeight = constraints.maxHeight)
            val placeables = measurables.map { it.measure(loose) }
            holder.size = IntSize(placeables.maxOfOrNull { it.width } ?: 0, placeables.maxOfOrNull { it.height } ?: 0)
            layout(constraints.maxWidth, constraints.maxHeight) { placeables.forEach { it.place(0, 0) } }
        }
    }

    private fun Throwable.describe(): String {
        val root = generateSequence(this) { it.cause?.takeIf { cause -> cause !== it } }.last()
        val at = root.stackTrace.firstOrNull { it.className.startsWith("hivens.") }
        return "${root::class.simpleName}: ${root.message}" + (at?.let { " at $it" } ?: "")
    }

    private companion object {
        const val OUT_PROPERTY = "nexira.probe.previews"
        val PREVIEW_DESCRIPTORS = setOf(
            "Landroidx/compose/ui/tooling/preview/Preview;",
            "Landroidx/compose/ui/tooling/preview/Preview\$Container;",
        )
        const val COMPOSABLE_NO_ARGS = "(Landroidx/compose/runtime/Composer;I)V"
        const val CANVAS_PX = 2800
        const val DENSITY = 2f
        val FRAMES_MS = longArrayOf(16, 32, 500, 3_000)
    }
}
