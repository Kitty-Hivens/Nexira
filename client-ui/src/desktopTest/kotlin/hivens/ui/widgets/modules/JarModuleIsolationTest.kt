package hivens.ui.widgets.modules

import androidx.compose.runtime.Composer
import com.materialkolor.hct.Hct
import dev.hivens.skinema.player.VideoPlayer
import hivens.accounts.AccountsModule
import hivens.accounts.SessionStore
import hivens.accounts.api.AccountKeys
import hivens.accounts.api.AccountsApi
import hivens.auth.AccountStore
import hivens.catalogue.BrowseState
import hivens.catalogue.CatalogueModule
import hivens.catalogue.api.CatalogueApi
import hivens.catalogue.api.CatalogueKeys
import hivens.core.api.interfaces.ICredentialStore
import hivens.launcher.modrinth.ModrinthClient
import hivens.packs.api.PackKeys
import hivens.packs.api.PacksApi
import hivens.themes.ThemeManager
import hivens.themes.api.ThemeContribution
import hivens.themes.api.ThemesApi
import hivens.ui.theme.NxInk
import hivens.widget.api.WidgetScope
import hivens.widget.loader.LoadedWidgetModule
import hivens.widget.loader.WidgetModuleLoader
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.json.Json
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.skia.Image
import org.jetbrains.skiko.SkiaLayer
import org.koin.core.Koin
import org.koin.core.context.GlobalContext
import org.slf4j.Logger
import java.io.DataInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import kotlin.io.path.copyTo
import kotlin.io.path.createDirectories
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * What a jar module's class loader answers, opened the way the launcher opens it:
 * the kernel, the design system and the shared libraries are the launcher's own
 * classes, a module API is there for a jar that names it, every class an example
 * module names is there for it, and the launcher itself and its Koin are not there at
 * all, though every one of them is on this test's class path.
 */
class JarModuleIsolationTest {

    private lateinit var tmp: Path
    private val opened = mutableListOf<LoadedWidgetModule>()

    private fun jar(property: String): Path = Path.of(requireNotNull(System.getProperty(property)) { "$property was not passed to the test JVM" })

    private val fixtureJar = jar("nexira.test.fixtureModuleJar")
    private val packsConsumerJar = jar("nexira.test.packsConsumerJar")
    private val accountsConsumerJar = jar("nexira.test.accountsConsumerJar")
    private val catalogueConsumerJar = jar("nexira.test.catalogueConsumerJar")
    private val pixelPlayerJar = jar("nexira.test.pixelPlayerJar")
    private val exampleJars = listOf(
        fixtureJar,
        packsConsumerJar,
        accountsConsumerJar,
        catalogueConsumerJar,
        pixelPlayerJar,
        jar("nexira.test.screenFxJar"),
        jar("nexira.test.looksJar"),
    )

    @BeforeTest
    fun setUp() {
        tmp = Files.createTempDirectory("jar-module-isolation-test-")
    }

    @AfterTest
    fun tearDown() {
        opened.forEach { val _ = runCatching { it.loader.close() } }
        Files.walk(tmp).use { walk -> walk.sorted(Comparator.reverseOrder()).forEach { val _ = runCatching { Files.deleteIfExists(it) } } }
    }

    /** [jar] loaded alone, by the launcher's loader with the APIs this build offers and the kinds of contribution it reads. */
    private fun load(jar: Path): LoadedWidgetModule {
        val dir = tmp.resolve("${opened.size}-${jar.fileName}").createDirectories()
        jar.copyTo(dir.resolve(jar.fileName))
        val scan = WidgetModuleLoader(
            directory         = dir,
            parent            = javaClass.classLoader,
            contributionKinds = offeredContributionKinds(),
            offered           = offeredApis(),
            apiPackages       = offeredApiPackages(),
        ).scan()
        assertEquals(emptyList(), scan.rejected, "${jar.fileName} was refused")
        return scan.loaded.single().also { opened += it }
    }

    /** What holds a token or the cached password, the launcher's internals and its Koin, by name. */
    private val hidden = listOf(
        SessionStore::class.java.name,
        AccountStore::class.java.name,
        ICredentialStore::class.java.name,
        "hivens.ui.host.LauncherHostAccessKt",
        "hivens.ui.host.HostKoinKt",
        WidgetModuleLoader::class.java.name,
        GlobalContext::class.java.name,
        "org.koin.compose.KoinApplicationKt",
        Koin::class.java.name,
    )

    @Test
    fun `the launcher's internals and its Koin are not found through a jar`() {
        val loader = load(fixtureJar).loader
        hidden.forEach { name ->
            // The launcher carries each one, so only the jar's view can be what hides it.
            javaClass.classLoader.loadClass(name)
            assertFailsWith<ClassNotFoundException>("$name was found through the jar") { loader.loadClass(name) }
            assertNull(loader.getResource(name.replace('.', '/') + ".class"), "$name was readable through the jar")
        }
    }

    @Test
    fun `the kernel and the shared libraries are the launcher's own classes`() {
        val loader = load(fixtureJar).loader
        listOf(
            WidgetScope::class.java,
            Composer::class.java,
            StringResource::class.java,
            Image::class.java,
            SkiaLayer::class.java,
            VideoPlayer::class.java,
            Unit::class.java,
            StateFlow::class.java,
            Json::class.java,
            Logger::class.java,
        ).forEach { shared ->
            assertSame(shared, loader.loadClass(shared.name), "${shared.name} is not the launcher's copy")
        }
    }

    @Test
    fun `the design system is shared by artifact, and the launcher's classes in its packages are not`() {
        val loader = load(fixtureJar).loader
        // One out of nx-ui, one out of the colour library shared beside it.
        assertSame(NxInk::class.java, loader.loadClass(NxInk::class.java.name))
        assertSame(Hct::class.java, loader.loadClass(Hct::class.java.name))
        assertSame(javaClass.classLoader.loadClass(NX_BUTTON), loader.loadClass(NX_BUTTON))
        // Out of client-ui, in the package nx-ui shares: on this class path, and not the jar's to see.
        javaClass.classLoader.loadClass(CLIENT_UI_THEME_CLASS)
        assertFailsWith<ClassNotFoundException> { loader.loadClass(CLIENT_UI_THEME_CLASS) }
    }

    @Test
    fun `a module API is served to a jar that names it, and no other`() {
        // packs-consumer names the packs API in its manifest and not the accounts one.
        val packs = load(packsConsumerJar).loader
        assertSame(PackKeys::class.java, packs.loadClass(PackKeys::class.java.name))
        assertFailsWith<ClassNotFoundException> { packs.loadClass(AccountKeys::class.java.name) }

        // The fixture names none.
        val fixture = load(fixtureJar).loader
        assertFailsWith<ClassNotFoundException> { fixture.loadClass(PackKeys::class.java.name) }
        assertFailsWith<ClassNotFoundException> { fixture.loadClass(AccountKeys::class.java.name) }
    }

    @Test
    fun `a jar that names the accounts API gets the API and nothing else of accounts`() {
        val accounts = load(accountsConsumerJar).loader
        assertSame(AccountKeys::class.java, accounts.loadClass(AccountKeys::class.java.name))
        listOf(SessionStore::class.java.name, AccountsModule::class.java.name, AccountStore::class.java.name, PackKeys::class.java.name).forEach { name ->
            assertFailsWith<ClassNotFoundException>("$name was found through accounts-consumer") { accounts.loadClass(name) }
        }
    }

    @Test
    fun `a jar that names the catalogue API gets the API and nothing else of the catalogue or the engine`() {
        val catalogue = load(catalogueConsumerJar).loader
        assertSame(CatalogueKeys::class.java, catalogue.loadClass(CatalogueKeys::class.java.name))
        listOf(
            BrowseState::class.java.name,
            CatalogueModule::class.java.name,
            ModrinthClient::class.java.name,
            PackKeys::class.java.name,
            AccountKeys::class.java.name,
        ).forEach { name ->
            assertFailsWith<ClassNotFoundException>("$name was found through catalogue-consumer") { catalogue.loadClass(name) }
        }
        // And the other example jars, which name no catalogue, do not see it either.
        assertFailsWith<ClassNotFoundException> { load(packsConsumerJar).loader.loadClass(CatalogueKeys::class.java.name) }
    }

    @Test
    fun `every API a compiled module offers says where its classes are`() {
        CompiledModules.forEach { module ->
            assertEquals(module.provides.keys, module.apiPackages.keys, "${module.id} offers an API without saying where its classes are")
            module.apiPackages.forEach { (api, packages) -> assertTrue(packages.isNotEmpty(), "${module.id} shares no package for $api") }
        }
        assertEquals(offeredApis().keys, offeredApiPackages().keys)
        assertEquals(setOf(PackKeys::class.java.name.substringBeforeLast('.')), offeredApiPackages().getValue(PacksApi.ID))
        assertEquals(setOf(AccountKeys::class.java.name.substringBeforeLast('.')), offeredApiPackages().getValue(AccountsApi.ID))
        assertEquals(setOf(CatalogueKeys::class.java.name.substringBeforeLast('.')), offeredApiPackages().getValue(CatalogueApi.ID))
        assertEquals(setOf(ThemeContribution::class.java.name.substringBeforeLast('.')), offeredApiPackages().getValue(ThemesApi.ID))
    }

    @Test
    fun `a jar that names the themes API brings its themes through it, read as the launcher reads them`() {
        val looks = load(jar("nexira.test.looksJar"))
        assertEquals(setOf("outrun", "paperback"), looks.contributions.filterIsInstance<ThemeContribution>().map { it.id }.toSet())
        assertSame(ThemeContribution::class.java, looks.loader.loadClass(ThemeContribution::class.java.name))
        assertFailsWith<ClassNotFoundException>("the themes module itself was found through the jar") {
            looks.loader.loadClass(ThemeManager::class.java.name)
        }
        assertTrue(ThemeContribution::class.java in offeredContributionKinds(), "the launcher reads the kind the themes module declares")
    }

    @Test
    fun `every class an example module names is there for it`() {
        exampleJars.forEach { jar ->
            val module = load(jar)
            val named = namedClasses(jar)
            assertTrue(named.isNotEmpty(), "${jar.fileName} names nothing, so this proves nothing about it")
            val missing = named.filter { name -> runCatching { module.loader.loadClass(name) }.isFailure }
            assertEquals(emptyList(), missing, "${jar.fileName} names classes its loader does not have")
        }
    }

    @Test
    fun `the pixel player plays through the launcher's media engine`() {
        val module = load(pixelPlayerJar)
        assertTrue(module.registry.all().isNotEmpty(), "the pixel player brought no widgets")
        val named = namedClasses(pixelPlayerJar)
        assertTrue(named.any { it.startsWith("dev.hivens.skinema.") }, "the pixel player no longer names the media engine")
        named.filter { it.startsWith("dev.hivens.skinema.") }.forEach { name ->
            assertSame(javaClass.classLoader.loadClass(name), module.loader.loadClass(name), "$name is not the launcher's")
        }
    }

    /**
     * Every class [jar]'s own class files refer to that the jar does not hold itself, by
     * binary name, read off their constant pools: what has to resolve through the
     * module's loader for its code to run.
     */
    private fun namedClasses(jar: Path): Set<String> = JarFile(jar.toFile()).use { file ->
        val entries = file.entries().toList().filter { it.name.endsWith(".class") && it.name != "module-info.class" }
        val own = entries.map { it.name.removeSuffix(".class").replace('/', '.') }.toSet()
        entries.flatMap { entry -> file.getInputStream(entry).use { classRefs(DataInputStream(it)) } }
            .mapNotNull(::binaryName)
            .filter { it !in own }
            .toSet()
    }

    /** The internal names of every class constant in one class file. */
    private fun classRefs(input: DataInputStream): List<String> {
        input.readInt()
        input.readUnsignedShort()
        input.readUnsignedShort()
        val count = input.readUnsignedShort()
        val utf8 = HashMap<Int, String>()
        val classIndexes = ArrayList<Int>()
        var index = 1
        while (index < count) {
            when (val tag = input.readUnsignedByte()) {
                1 -> utf8[index] = input.readUTF()
                7 -> classIndexes += input.readUnsignedShort()
                8, 16, 19, 20 -> input.readUnsignedShort()
                15 -> {
                    input.readUnsignedByte()
                    input.readUnsignedShort()
                }
                3, 4, 9, 10, 11, 12, 17, 18 -> input.readInt()
                5, 6 -> {
                    input.readLong()
                    index++
                }
                else -> error("constant pool tag $tag")
            }
            index++
        }
        return classIndexes.mapNotNull { utf8[it] }
    }

    /** An internal name or an array descriptor as the binary name to load, or null for an array of primitives. */
    private fun binaryName(internal: String): String? {
        val element = internal.trimStart('[')
        val name = when {
            element.length == 1 -> return null
            element.startsWith("L") && element.endsWith(";") && element != internal -> element.substring(1, element.length - 1)
            else -> element
        }
        return name.replace('/', '.')
    }

    private companion object {
        /** A file facade of nx-ui in `hivens.ui.nx`. */
        const val NX_BUTTON = "hivens.ui.nx.NxButtonKt"

        /** A file facade of client-ui in `hivens.ui.theme`, the package nx-ui shares. */
        const val CLIENT_UI_THEME_CLASS = "hivens.ui.theme.BrandColorsKt"
    }
}
