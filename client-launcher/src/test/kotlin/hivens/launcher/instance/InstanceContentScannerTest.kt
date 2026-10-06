package hivens.launcher.instance

import jetbrains.exodus.env.Environments
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class InstanceContentScannerTest {

    @TempDir lateinit var dir: Path

    private fun zip(target: Path, entries: Map<String, ByteArray>) {
        Files.createDirectories(target.parent)
        ZipOutputStream(Files.newOutputStream(target)).use { zos ->
            entries.forEach { (name, bytes) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
    }

    @Test
    fun `parses fabric mod name, version and icon and detects disabled`() = runBlocking {
        val mods = dir.resolve("mods")
        zip(mods.resolve("sodium-fabric-0.6.0-mc1.21.jar"), mapOf(
            "fabric.mod.json" to """{"id":"sodium","name":"Sodium","version":"0.6.0","description":"Fast","icon":"icon.png"}""".toByteArray(),
            "icon.png" to byteArrayOf(1, 2, 3, 4),
        ))
        zip(mods.resolve("iris-1.8.0.jar.disabled"), mapOf(
            "fabric.mod.json" to """{"id":"iris","name":"Iris Shaders","version":"1.8.0"}""".toByteArray(),
        ))

        val items = InstanceContentScanner().scan(dir)

        val sodium = items.first { it.fileName == "sodium-fabric-0.6.0-mc1.21.jar" }
        assertEquals("Sodium", sodium.displayName)
        assertEquals("0.6.0", sodium.version)
        assertTrue(sodium.enabled)
        assertNotNull(sodium.iconBytes)
        assertEquals(4, sodium.iconBytes!!.size)

        val iris = items.first { it.fileName == "iris-1.8.0.jar" }
        assertEquals("Iris Shaders", iris.displayName)
        assertFalse(iris.enabled, "a .disabled jar reads as disabled")
        assertEquals(ContentKind.Mod, iris.kind)
    }

    @Test
    fun `falls back to a cleaned filename when a jar carries no metadata`() = runBlocking {
        zip(dir.resolve("mods").resolve("SomeMod-1.2.3.jar"), mapOf("META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\n".toByteArray()))

        val item = InstanceContentScanner().scan(dir).single()
        assertEquals("SomeMod", item.displayName)
        assertNull(item.version)
    }

    @Test
    fun `reads resource pack description and classifies by folder`() = runBlocking {
        zip(dir.resolve("resourcepacks").resolve("Faithful.zip"), mapOf(
            "pack.mcmeta" to """{"pack":{"pack_format":15,"description":"Faithful 32x"}}""".toByteArray(),
            "pack.png" to byteArrayOf(9, 9),
        ))

        val item = InstanceContentScanner().scan(dir).single()
        assertEquals(ContentKind.ResourcePack, item.kind)
        assertEquals("Faithful 32x", item.description)
        assertNotNull(item.iconBytes)
    }

    @Test
    fun `toml display name keeps apostrophes inside double quotes`() = runBlocking {
        zip(dir.resolve("mods").resolve("brewin.jar"), mapOf(
            "META-INF/mods.toml" to """
                modId = "brewin"
                version = "4.4.2"
                displayName = "Brewin' And Chewin'"
                authors = "Mim's Friends, Someone"
            """.trimIndent().toByteArray(),
        ))

        val item = InstanceContentScanner().scan(dir).single()
        assertEquals("Brewin' And Chewin'", item.displayName)
        assertEquals("4.4.2", item.version)
        assertEquals(listOf("Mim's Friends", "Someone"), item.authors)
    }

    @Test
    fun `a dummy fabric stub loses to the real toml`() = runBlocking {
        zip(dir.resolve("mods").resolve("forgemod.jar"), mapOf(
            "fabric.mod.json" to """{"id":"stub","name":"NOT A FABRIC MOD","version":"not a fabric mod"}""".toByteArray(),
            "META-INF/neoforge.mods.toml" to """
                modId = "realmod"
                version = "2.0.0"
                displayName = "Real Mod"
            """.trimIndent().toByteArray(),
        ))

        val item = InstanceContentScanner().scan(dir).single()
        assertEquals("Real Mod", item.displayName)
        assertEquals("2.0.0", item.version)
    }

    @Test
    fun `jarVersion placeholder resolves from the manifest`() = runBlocking {
        val placeholder = "\${file.jarVersion}"
        zip(dir.resolve("mods").resolve("arsnouveau.jar"), mapOf(
            "META-INF/neoforge.mods.toml" to """
                modId = "ars"
                version = "$placeholder"
                displayName = "Ars Nouveau"
            """.trimIndent().toByteArray(),
            "META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\nImplementation-Version: 5.9.2\n".toByteArray(),
        ))

        val item = InstanceContentScanner().scan(dir).single()
        assertEquals("Ars Nouveau", item.displayName)
        assertEquals("5.9.2", item.version)
    }

    @Test
    fun `bound icon processor rewrites both the returned item and the cached entry`() = runBlocking {
        val mods = dir.resolve("mods")
        val jar = mods.resolve("big-icon.jar")
        zip(jar, mapOf(
            "fabric.mod.json" to """{"id":"big","name":"Big","version":"1.0","icon":"icon.png"}""".toByteArray(),
            "icon.png" to ByteArray(512) { it.toByte() },
        ))
        val marker = byteArrayOf(7, 7, 7)
        val env = Environments.newInstance(Files.createTempDirectory("scanenv").toFile())
        try {
            val cache = ContentScanCache(env, "content-scan", Json { ignoreUnknownKeys = true })
            val items = InstanceContentScanner(cache, icons = { marker }).scan(dir)
            assertTrue(marker.contentEquals(items.single().iconBytes), "returned item carries the processed icon")

            val size = Files.size(jar)
            val mtime = Files.getLastModifiedTime(jar).toMillis()
            val hit = cache.lookup(jar.normalize().toString(), size, mtime)
            assertTrue(marker.contentEquals(hit?.meta?.icon), "cache stores the processed icon, not the original")
        } finally {
            env.close()
        }
    }

    @Test
    fun `processor dropping the icon leaves the rest of the metadata intact`() = runBlocking {
        zip(dir.resolve("mods").resolve("dropped.jar"), mapOf(
            "fabric.mod.json" to """{"id":"d","name":"Dropped","version":"2.0","icon":"icon.png"}""".toByteArray(),
            "icon.png" to ByteArray(64),
        ))

        val item = InstanceContentScanner(icons = { null }).scan(dir).single()
        assertEquals("Dropped", item.displayName)
        assertEquals("2.0", item.version)
        assertNull(item.iconBytes)
    }

    private fun jarBytes(entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zos ->
            entries.forEach { (name, bytes) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun `a mods toml gives its ids and its hard dependencies with their ranges`() = runBlocking {
        val toml = listOf(
            "modLoader=\"javafml\"",
            "[[mods]]",
            "modId=\"reeses_sodium_options\"",
            "version=\"\${file.jarVersion}\"",
            "description='''",
            "[[dependencies.fake]]",
            "modId=\"not_a_real_dependency\"",
            "'''",
            "[[dependencies.reeses_sodium_options]]",
            "modId=\"sodium\"",
            "type=\"required\"",
            "versionRange=\"[0.8.12,)\"",
            "side=\"CLIENT\"",
            "[[dependencies.reeses_sodium_options]]",
            "modId=\"iris\"",
            "type=\"optional\"",
            "versionRange=\"[1.0,)\"",
            "[[dependencies.reeses_sodium_options]]",
            "modId = 'serverthing'",
            "mandatory = true",
            "side = \"SERVER\"",
            "[[dependencies.reeses_sodium_options]]",
            "modId=\"old_forge_optional\"",
            "mandatory=false",
            "# [[dependencies.reeses_sodium_options]]",
            "# modId=\"commented_out\"",
        ).joinToString("\n")
        zip(dir.resolve("mods/reeses.jar"), mapOf(
            "META-INF/neoforge.mods.toml" to toml.toByteArray(),
            "META-INF/MANIFEST.MF" to "Manifest-Version: 1.0\nImplementation-Version: 1.8.3\n".toByteArray(),
        ))

        val item = InstanceContentScanner().scan(dir).single()

        assertEquals(listOf(ProvidedMod("reeses_sodium_options", "1.8.3")), item.provides)
        assertEquals(listOf(ModRequirement("sodium", listOf("[0.8.12,)"), RangeScheme.Maven, "neoforge")), item.requires)
    }

    @Test
    fun `a fabric mod provides its nested jars and requires what it depends on`() = runBlocking {
        val nested = jarBytes(mapOf("fabric.mod.json" to """{"id":"fabric-api-base","version":"0.4.42"}""".toByteArray()))
        zip(dir.resolve("mods/fabric-api.jar"), mapOf(
            "fabric.mod.json" to """{"id":"fabric-api","version":"0.100.0","provides":["fabric"],"depends":{"fabricloader":">=0.15","minecraft":["1.21","1.21.1"]}}""".toByteArray(),
            "META-INF/jars/fabric-api-base.jar" to nested,
        ))

        val item = InstanceContentScanner().scan(dir).single()

        assertEquals(
            setOf(ProvidedMod("fabric-api", "0.100.0"), ProvidedMod("fabric", "0.100.0"), ProvidedMod("fabric-api-base", "0.4.42")),
            item.provides.toSet(),
        )
        assertEquals(
            listOf(
                ModRequirement("fabricloader", listOf(">=0.15"), RangeScheme.Fabric, "fabric"),
                ModRequirement("minecraft", listOf("1.21", "1.21.1"), RangeScheme.Fabric, "fabric"),
            ),
            item.requires,
        )
    }

    @Test
    fun `a template header with a comment after it is still a table`() = runBlocking {
        val toml = listOf(
            "﻿modLoader=\"javafml\" #mandatory",
            "[[mods]] #mandatory",
            "modId=\"examplemod\" #mandatory",
            "version=\"1.0.0\"",
            "[[dependencies.examplemod]] #optional",
            "modId=\"examplelib\" #mandatory",
            "mandatory=true",
            "versionRange=\"[2.0,)\"",
        ).joinToString("\r\n")
        zip(dir.resolve("mods/example.jar"), mapOf("META-INF/mods.toml" to toml.toByteArray()))

        val item = InstanceContentScanner().scan(dir).single()

        assertEquals(listOf(ProvidedMod("examplemod", "1.0.0")), item.provides)
        assertEquals(listOf(ModRequirement("examplelib", listOf("[2.0,)"), RangeScheme.Maven, "forge")), item.requires)
    }

    @Test
    fun `a jar whose mod is only nested inside it provides that mod`() = runBlocking {
        val inner = jarBytes(mapOf(
            "META-INF/mods.toml" to "[[mods]]\nmodId=\"kotlinforforge\"\nversion=\"4.11.0\"\n".toByteArray(),
        ))
        zip(dir.resolve("mods/kotlinforforge-all.jar"), mapOf("META-INF/jarjar/kffmod.jar" to inner))

        val item = InstanceContentScanner().scan(dir).single()

        assertEquals(listOf(ProvidedMod("kotlinforforge", "4.11.0")), item.provides)
    }

    @Test
    fun `a multi-loader jar provides every name and keeps each loader's requirements apart`() = runBlocking {
        zip(dir.resolve("mods/both.jar"), mapOf(
            "META-INF/neoforge.mods.toml" to "[[mods]]\nmodId=\"both_mod\"\nversion=\"1.0\"\n[[dependencies.both_mod]]\nmodId=\"kotlinforforge\"\ntype=\"required\"\n".toByteArray(),
            "fabric.mod.json" to """{"id":"both-mod","version":"1.0","depends":{"fabric-language-kotlin":"*"}}""".toByteArray(),
        ))

        val item = InstanceContentScanner().scan(dir).single()

        assertEquals(setOf("both_mod", "both-mod"), item.provides.map { it.id }.toSet())
        assertEquals(setOf("neoforge", "fabric"), item.requires.map { it.loader }.toSet())
    }

    @Test
    fun `a server-side fabric mod requires nothing of a client`() = runBlocking {
        zip(dir.resolve("mods/server.jar"), mapOf(
            "fabric.mod.json" to """{"id":"serverthing","version":"1","environment":"server","depends":{"lib":"*"}}""".toByteArray(),
        ))
        assertTrue(InstanceContentScanner().scan(dir).single().requires.isEmpty())
    }
}
