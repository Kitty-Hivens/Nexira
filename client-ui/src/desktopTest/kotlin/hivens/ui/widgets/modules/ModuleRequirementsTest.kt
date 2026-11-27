package hivens.ui.widgets.modules

import hivens.widget.model.StandardDocument
import hivens.accounts.api.AccountsApi
import hivens.catalogue.api.CatalogueApi
import hivens.i18n.api.I18nApi
import hivens.look.manager.api.LookApi
import hivens.look.motion.nx.MotionNxModule
import hivens.packs.api.PacksApi
import hivens.themes.api.ThemesApi
import hivens.widget.api.CompiledModule
import hivens.widget.api.ModuleContribution
import hivens.widget.model.ApiVersion
import java.lang.classfile.ClassFile
import java.lang.classfile.constantpool.Utf8Entry
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.readBytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import hivens.history.api.HistoryApi

/**
 * What the modules compiled into the build say of each other is what their classes do: every API of
 * another module a module's code names is one it requires or uses, and it declares no other. All of them
 * run, and what one requires comes in with it.
 */
class ModuleRequirementsTest {

    private val packagesOf: Map<String, Set<String>> = offeredApiPackages().filterValues { it.isNotEmpty() }

    @Test
    fun `every module compiled in runs, with no circle and nothing missing`() {
        val graph = moduleGraph()
        assertEquals(emptyMap(), graph.waiting)
        assertEquals(CompiledModules.map { it.id }.toSet(), graph.order.toSet())
    }

    @Test
    fun `a module declares the APIs its classes name, and no other`() {
        val wrong = CompiledModules.mapNotNull { module ->
            val named = apisNamedBy(module) - module.provides.keys
            val declared = (module.requires.keys + module.uses.keys).filter { packagesOf[it] != null }.toSet()
            if (named == declared) null else "${module.id} names ${(named - declared)} undeclared and declares ${(declared - named)} for nothing"
        }
        assertEquals(emptyList(), wrong)
    }

    @Test
    fun `a module does not both require and use one thing, and names only what is offered`() {
        CompiledModules.forEach { module ->
            assertEquals(emptySet(), module.requires.keys intersect module.uses.keys, module.id)
            assertEquals(emptySet(), (module.requires.keys + module.uses.keys) - offeredApis().keys, module.id)
        }
    }

    @Test
    fun `what a named module requires is brought in with it, and what it uses is not`() {
        assertEquals(setOf("nexira.source.mirror", CatalogueApi.ID), broughtIn(setOf("nexira.source.mirror"), CompiledModules))
        assertEquals(setOf(PacksApi.ID), broughtIn(setOf(PacksApi.ID), CompiledModules), "the packs use the catalogue and run without")
        assertEquals(setOf(ThemesApi.ID), broughtIn(setOf(ThemesApi.ID), CompiledModules))
        assertEquals(emptySet(), broughtIn(emptySet(), CompiledModules), "a document that names nothing brings in nothing")
        assertEquals(setOf("someone.else"), broughtIn(setOf("someone.else"), CompiledModules), "a name nothing carries stays as it is")
    }

    @Test
    fun `a module is required by its id where it states a version`() {
        val brought = broughtIn(setOf("nexira.look.motion.nx.controls"), CompiledModules)
        assertEquals(setOf("nexira.look.motion.nx.controls", MotionNxModule.ID, LookApi.ID), brought)
    }

    @Test
    fun `what the jars require is brought in, by the API or the id they ask for it by, and what no module here offers is left to the jars`() {
        val brought = broughtIn(setOf(I18nApi.ID), CompiledModules, jarsRequire = setOf(AccountsApi.ID, "wobble"))
        assertEquals(setOf(I18nApi.ID, AccountsApi.ID), brought)
    }

    // A document of a person's own keeps the modules it was written with, so one written before the history was a
    // module names none, and the rail, the breadcrumb and the home page would draw nothing in it.
    @Test
    fun `what the built-in widgets require is brought in whatever the document names`() {
        assertEquals(setOf(HistoryApi.ID), broughtIn(emptySet(), CompiledModules, builtInRequire = BuiltInWidgetsRequire))
    }

    @Test
    fun `the standard document names what its modules require, but for what comes in by itself`() {
        val named = StandardDocument.load().modules.toSet()
        assertEquals(setOf(MotionNxModule.ID), broughtIn(named, CompiledModules) - named)
    }

    @Test
    fun `a module of an API it publishes and a version it states is offered by both`() {
        val module = object : CompiledModule {
            override val id = "test.base"
            override val name = id
            override val version = ApiVersion(1, 3)
            override val provides = mapOf("test.base.api" to ApiVersion(2, 0))
            override val contributions: List<ModuleContribution> = emptyList()
        }
        assertEquals(mapOf("test.base" to ApiVersion(1, 3), "test.base.api" to ApiVersion(2, 0)), offeredApis(listOf(module)))
        assertEquals(emptySet(), offeredApiPackages(listOf(module)).getValue("test.base"), "a module offered by its id shares no classes for it")
    }

    /** The APIs whose packages the classes [module] is built of name, its own contract's classes left out. */
    private fun apisNamedBy(module: CompiledModule): Set<String> {
        val root = Paths.get(module.javaClass.protectionDomain.codeSource.location.toURI())
        val names = if (root.isDirectory()) namesIn(root) else FileSystems.newFileSystem(root).use { namesIn(it.getPath("/")) }
        assertTrue(names.isNotEmpty(), "${module.id} has no classes at $root")
        return packagesOf.filterValues { packages -> packages.any { pkg -> names.any { it.startsWith(pkg.replace('.', '/') + "/") } } }.keys
    }

    /** Every class any class file under [root] names, in its descriptors as well. */
    private fun namesIn(root: Path): Set<String> = Files.walk(root).use { paths -> paths.filter { it.extension == "class" }.toList() }
        .flatMap { file ->
            ClassFile.of().parse(file.readBytes()).constantPool().filterIsInstance<Utf8Entry>()
                .flatMap { entry -> NAME.findAll(entry.stringValue()).map { it.value }.toList() }
        }
        .toSet()

    private companion object {
        val NAME = Regex("""hivens(/[A-Za-z0-9_$]+)+""")
    }
}
