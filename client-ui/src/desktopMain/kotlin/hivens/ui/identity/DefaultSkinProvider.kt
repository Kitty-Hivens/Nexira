package hivens.ui.identity

import hivens.core.io.AtomicFiles
import org.slf4j.LoggerFactory
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries

/**
 * Minecraft's default player skins, resolved from a client jar the launcher has
 * already provisioned -- never bundled. The textures stay Mojang's: we read them
 * out of the user's own downloaded client (the same jar that runs the game) and
 * cache the PNGs locally, so nothing official is redistributed.
 *
 * Modern clients (1.19.4+) ship the nine-skin set under
 * `assets/minecraft/textures/entity/player/{wide,slim}/<name>.png`; pre-1.19.4
 * clients only carry Steve + Alex at `entity/<name>.png`. We prefer a modern jar
 * and fall back to the legacy pair. Extraction happens once into [cacheDir]; later
 * calls read the cache.
 *
 * [clientsDir] is `<dataDir>/clients`, holding `<pack>/bin/<loader>-<version>.jar`.
 */
class DefaultSkinProvider(private val clientsDir: Path, private val cacheDir: Path) {
    private val log = LoggerFactory.getLogger(DefaultSkinProvider::class.java)

    data class DefaultSkin(val name: String, val slim: Boolean, val file: Path)

    // The nine defaults in their canonical arm model (Mojang's assignment).
    private val modern = listOf(
        "Steve" to false, "Alex" to true, "Ari" to false, "Efe" to true, "Kai" to false,
        "Makena" to true, "Noor" to true, "Sunny" to false, "Zuri" to false,
    )

    /**
     * The available default skins, extracting from a client jar on first call.
     * Empty when no provisioned client carries player textures yet (e.g. before any
     * pack is installed); partial is impossible -- a jar either has the full set or
     * we fall back to the legacy pair.
     */
    fun list(): List<DefaultSkin> = runCatching { resolve() }.getOrElse {
        log.warn("default-skin resolve failed: {}", it.message)
        emptyList()
    }

    private fun resolve(): List<DefaultSkin> {
        cachedModern()?.let { return it }

        findClientJar(::hasModernTextures)?.let { jar ->
            ZipFile(jar.toFile()).use { zip ->
                val out = modern.mapNotNull { (name, slim) -> extractModern(zip, name, slim) }
                if (out.isNotEmpty()) return out
            }
        }

        // Legacy clients (1.12.2 etc.): only Steve + Alex, old texture path.
        findClientJar(::hasLegacyTextures)?.let { jar ->
            ZipFile(jar.toFile()).use { zip ->
                return listOfNotNull(
                    extractLegacy(zip, "Steve", slim = false),
                    extractLegacy(zip, "Alex", slim = true),
                )
            }
        }
        return emptyList()
    }

    private fun cachedModern(): List<DefaultSkin>? {
        val hit = modern.mapNotNull { (name, slim) ->
            val f = cacheDir.resolve("${name.lowercase()}.png")
            if (f.exists()) DefaultSkin(name, slim, f) else null
        }
        return hit.takeIf { it.size == modern.size }
    }

    private fun extractModern(zip: ZipFile, name: String, slim: Boolean): DefaultSkin? {
        val out = cacheDir.resolve("${name.lowercase()}.png")
        if (!out.exists()) {
            // Prefer the canonical model dir, fall back to the other if absent.
            val dirs = if (slim) listOf("slim", "wide") else listOf("wide", "slim")
            val bytes = dirs.firstNotNullOfOrNull { d ->
                zip.getEntry("assets/minecraft/textures/entity/player/$d/${name.lowercase()}.png")
                    ?.let { zip.getInputStream(it).use { s -> s.readBytes() } }
            } ?: return null
            AtomicFiles.writeBytes(out, bytes)
        }
        return DefaultSkin(name, slim, out)
    }

    private fun extractLegacy(zip: ZipFile, name: String, slim: Boolean): DefaultSkin? {
        val out = cacheDir.resolve("${name.lowercase()}.png")
        if (!out.exists()) {
            val entry = zip.getEntry("assets/minecraft/textures/entity/${name.lowercase()}.png") ?: return null
            AtomicFiles.writeBytes(out, zip.getInputStream(entry).use { it.readBytes() })
        }
        return DefaultSkin(name, slim, out)
    }

    private fun findClientJar(carries: (Path) -> Boolean): Path? {
        if (!clientsDir.isDirectory()) return null
        for (pack in clientsDir.listDirectoryEntries()) {
            val bin = pack.resolve("bin")
            if (!bin.isDirectory()) continue
            for (jar in bin.listDirectoryEntries("*.jar")) if (carries(jar)) return jar
        }
        return null
    }

    private fun hasModernTextures(jar: Path) = jarHasEntry(jar, "assets/minecraft/textures/entity/player/wide/steve.png")
    private fun hasLegacyTextures(jar: Path) = jarHasEntry(jar, "assets/minecraft/textures/entity/steve.png")

    private fun jarHasEntry(jar: Path, entry: String): Boolean =
        runCatching { ZipFile(jar.toFile()).use { it.getEntry(entry) != null } }.getOrDefault(false)
}
