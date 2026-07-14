package hivens.launcher.instance

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name

enum class ContentKind { Mod, ResourcePack, ShaderPack }

/**
 * One installed content item, read from the instance's own folders -- NOT from a
 * pack manifest. [fileName] is the on-disk name with any `.disabled` suffix
 * stripped, so a toggle is just a rename. [iconBytes] is the icon extracted from
 * the archive (fabric `icon`, forge `logoFile`, resource-pack `pack.png`) or null.
 * [homepageUrl] / [license] / [authors] / [dependencies] come from the archive's
 * own metadata (fabric.mod.json / quilt.mod.json / forge mods.toml) -- the offline,
 * origin-agnostic source the details view reads first, before any Modrinth lookup.
 */
class InstalledContent(
    val kind: ContentKind,
    val fileName: String,
    val displayName: String,
    val version: String?,
    val description: String?,
    val enabled: Boolean,
    val iconBytes: ByteArray?,
    val sizeBytes: Long,
    val homepageUrl: String? = null,
    val license: String? = null,
    val authors: List<String> = emptyList(),
    val dependencies: List<String> = emptyList(),
) {
    // Identity for Compose list diffing excludes the icon bytes (a fresh array
    // each scan would otherwise read as a change); the file name + state is what
    // a row actually renders on.
    override fun equals(other: Any?): Boolean =
        other is InstalledContent &&
            kind == other.kind && fileName == other.fileName &&
            enabled == other.enabled && version == other.version
    override fun hashCode(): Int =
        (((kind.hashCode() * 31 + fileName.hashCode()) * 31) + enabled.hashCode()) * 31 + (version?.hashCode() ?: 0)
}

/**
 * Reads what is ACTUALLY installed under an instance, origin-agnostic: the
 * `mods/`, `resourcepacks/` and `shaderpacks/` folders, parsing each archive's
 * own metadata for a display name / version / icon. Mirror packs, Modrinth packs
 * and from-scratch instances all read the same way -- the Content tab no longer
 * depends on a server manifest. One corrupt archive is logged and skipped.
 */
class InstanceContentScanner {

    private val log = LoggerFactory.getLogger(InstanceContentScanner::class.java)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun scan(instanceDir: Path): List<InstalledContent> = withContext(Dispatchers.IO) {
        buildList {
            addAll(scanArchives(instanceDir.resolve("mods"), ContentKind.Mod))
            addAll(scanArchives(instanceDir.resolve("resourcepacks"), ContentKind.ResourcePack))
            addAll(scanArchives(instanceDir.resolve("shaderpacks"), ContentKind.ShaderPack))
        }.sortedBy { it.displayName.lowercase() }
    }

    private fun scanArchives(dir: Path, kind: ContentKind): List<InstalledContent> {
        if (!dir.isDirectory()) return emptyList()
        return Files.list(dir).use { stream ->
            stream
                .filter { it.isRegularFile() && isArchive(it.name) }
                .map { runCatching { read(it, kind) }.getOrElse { e ->
                    log.warn("Skipping unreadable content at {}: {}", it, e.message)
                    null
                } }
                .filter { it != null }
                .map { it!! }
                .toList()
        }
    }

    private fun isArchive(name: String): Boolean {
        val base = name.removeSuffix(DISABLED)
        return base.endsWith(".jar") || base.endsWith(".zip")
    }

    private fun read(file: Path, kind: ContentKind): InstalledContent {
        val rawName = file.name
        val enabled = !rawName.endsWith(DISABLED)
        val fileName = rawName.removeSuffix(DISABLED)
        val size = Files.size(file)

        val meta = when (kind) {
            ContentKind.Mod -> readModMeta(file)
            ContentKind.ResourcePack -> readPackMeta(file)
            ContentKind.ShaderPack -> null
        }
        return InstalledContent(
            kind        = kind,
            fileName    = fileName,
            displayName = meta?.name?.takeIf { it.isNotBlank() } ?: prettyName(fileName),
            version     = meta?.version?.takeIf { it.isNotBlank() },
            description = meta?.description?.takeIf { it.isNotBlank() },
            enabled     = enabled,
            iconBytes   = meta?.icon,
            sizeBytes   = size,
            homepageUrl = meta?.homepageUrl?.takeIf { it.isNotBlank() },
            license     = meta?.license?.takeIf { it.isNotBlank() },
            authors     = meta?.authors.orEmpty(),
            dependencies = meta?.dependencies.orEmpty(),
        )
    }

    /** Fabric/Quilt first (the common modern case), then a light Forge/NeoForge TOML read. */
    private fun readModMeta(file: Path): Meta? = ZipFile(file.toFile()).use { zip ->
        zip.getEntry("fabric.mod.json")?.let { return parseFabric(zip, it) }
        zip.getEntry("quilt.mod.json")?.let { return parseQuilt(zip, it) }
        (zip.getEntry("META-INF/neoforge.mods.toml") ?: zip.getEntry("META-INF/mods.toml"))
            ?.let { return parseForgeToml(zip, it) }
        null
    }

    private fun parseFabric(zip: ZipFile, entry: java.util.zip.ZipEntry): Meta {
        val root = json.parseToJsonElement(zip.getInputStream(entry).readBytes().decodeToString()).jsonObject
        val name = root["name"]?.jsonPrimitive?.contentOrNull
        val version = root["version"]?.jsonPrimitive?.contentOrNull
        val description = root["description"]?.jsonPrimitive?.contentOrNull
        // `icon` is either a path string or a {size: path} object -- take the largest.
        val iconPath = root["icon"]?.let { el ->
            runCatching { el.jsonPrimitive.contentOrNull }.getOrNull()
                ?: runCatching { el.jsonObject.entries.maxByOrNull { it.key.toIntOrNull() ?: 0 }?.value?.jsonPrimitive?.contentOrNull }.getOrNull()
        }
        val contact = root["contact"]?.let { runCatching { it.jsonObject }.getOrNull() }
        val homepage = firstString(contact?.get("homepage")) ?: firstString(contact?.get("sources"))
        // `authors` entries are bare strings or `{ name, contact }` objects.
        val authors = stringList(root["authors"]) { el ->
            runCatching { el.jsonPrimitive.contentOrNull }.getOrNull()
                ?: runCatching { el.jsonObject["name"]?.jsonPrimitive?.contentOrNull }.getOrNull()
        }
        val depends = (root["depends"]?.let { runCatching { it.jsonObject.keys.toList() }.getOrNull() }.orEmpty())
            .filterNot { it in PLATFORM_DEPS }
        return Meta(
            name, version, description, iconPath?.let { readEntryBytes(zip, it) },
            homepageUrl = homepage, license = firstString(root["license"]), authors = authors, dependencies = depends,
        )
    }

    private fun parseQuilt(zip: ZipFile, entry: java.util.zip.ZipEntry): Meta {
        val loader = json.parseToJsonElement(zip.getInputStream(entry).readBytes().decodeToString())
            .jsonObject["quilt_loader"]?.jsonObject
        val meta = loader?.get("metadata")?.jsonObject
        val name = meta?.get("name")?.jsonPrimitive?.contentOrNull
        val version = meta?.get("version")?.jsonPrimitive?.contentOrNull
        val description = meta?.get("description")?.jsonPrimitive?.contentOrNull
        val iconPath = meta?.get("icon")?.jsonPrimitive?.contentOrNull
        val contact = meta?.get("contact")?.let { runCatching { it.jsonObject }.getOrNull() }
        val homepage = firstString(contact?.get("homepage")) ?: firstString(contact?.get("sources"))
        // `contributors` is a { name: role } object.
        val authors = meta?.get("contributors")?.let { runCatching { it.jsonObject.keys.toList() }.getOrNull() }.orEmpty()
        // `quilt_loader.depends` is an array of `{ id }` objects (or bare id strings).
        val depends = (loader?.get("depends")?.let { runCatching { it.jsonArray }.getOrNull() }.orEmpty())
            .mapNotNull { el ->
                runCatching { el.jsonObject["id"]?.jsonPrimitive?.contentOrNull }.getOrNull()
                    ?: runCatching { el.jsonPrimitive.contentOrNull }.getOrNull()
            }
            .filterNot { it in PLATFORM_DEPS }
        return Meta(
            name, version, description, iconPath?.let { readEntryBytes(zip, it) },
            homepageUrl = homepage, license = firstString(meta?.get("license")), authors = authors, dependencies = depends,
        )
    }

    /**
     * TOML has no bundled parser; a `key = "value"` line scan covers the fields we
     * show. Dependencies are left out here: forge/neoforge `[[dependencies.<modid>]]`
     * blocks need section-aware parsing the flat line scan can't do reliably.
     */
    private fun parseForgeToml(zip: ZipFile, entry: java.util.zip.ZipEntry): Meta {
        val text = zip.getInputStream(entry).readBytes().decodeToString()
        val name = TOML_DISPLAY_NAME.find(text)?.groupValues?.get(1)
        val version = TOML_VERSION.find(text)?.groupValues?.get(1)?.takeIf { !it.startsWith($$"${") }
        val logo = TOML_LOGO.find(text)?.groupValues?.get(1)
        val homepage = TOML_DISPLAY_URL.find(text)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
        val license = TOML_LICENSE.find(text)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
        val authors = TOML_AUTHORS.find(text)?.groupValues?.get(1)
            ?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()
        return Meta(
            name, version, null, logo?.let { readEntryBytes(zip, it) },
            homepageUrl = homepage, license = license, authors = authors,
        )
    }

    /** A field that may be a bare string OR an array of strings -- take the first usable value. */
    private fun firstString(el: JsonElement?): String? {
        el ?: return null
        runCatching { el.jsonPrimitive.contentOrNull }.getOrNull()?.let { return it }
        return runCatching { el.jsonArray.firstNotNullOfOrNull { it.jsonPrimitive.contentOrNull } }.getOrNull()
    }

    /** Map a JSON array (strings or objects) through [transform] to a trimmed, non-blank list. */
    private fun stringList(el: JsonElement?, transform: (JsonElement) -> String?): List<String> {
        el ?: return emptyList()
        val arr = runCatching { el.jsonArray }.getOrNull()
            ?: return listOfNotNull(transform(el)).map { it.trim() }.filter { it.isNotBlank() }
        return arr.mapNotNull(transform).map { it.trim() }.filter { it.isNotBlank() }
    }

    /** Resource pack: `pack.mcmeta` description + `pack.png` icon. */
    private fun readPackMeta(file: Path): Meta? = ZipFile(file.toFile()).use { zip ->
        val description = zip.getEntry("pack.mcmeta")?.let {
            runCatching {
                json.parseToJsonElement(zip.getInputStream(it).readBytes().decodeToString())
                    .jsonObject["pack"]?.jsonObject?.get("description")?.jsonPrimitive?.contentOrNull
            }.getOrNull()
        }
        Meta(null, null, description, readEntryBytes(zip, "pack.png"))
    }

    private fun readEntryBytes(zip: ZipFile, path: String): ByteArray? =
        zip.getEntry(path.removePrefix("/"))?.let { zip.getInputStream(it).readBytes() }

    /**
     * Last-resort icon probe: an archive whose metadata declares NO icon may still
     * physically carry one (a bare `icon.png` at the root, or the Fabric-convention
     * `assets/<modid>/icon.png` left undeclared). Used as the fallback after a
     * remote (Modrinth) lookup misses, so such a mod shows its own art instead of a
     * letter. Returns null when the archive truly has no recognizable icon.
     */
    fun probeJarIcon(file: Path): ByteArray? = runCatching {
        ZipFile(file.toFile()).use { zip ->
            for (name in ICON_CANDIDATES) {
                val entry = zip.getEntry(name) ?: continue
                return@use zip.getInputStream(entry).readBytes()
            }
            val nested = zip.entries().asSequence().firstOrNull {
                !it.isDirectory && it.name.startsWith("assets/") && it.name.endsWith("/icon.png")
            }
            nested?.let { zip.getInputStream(it).readBytes() }
        }
    }.getOrNull()

    /** Drop the extension and trailing version/loader tokens so a bare filename reads cleaner. */
    private fun prettyName(fileName: String): String =
        fileName.removeSuffix(".jar").removeSuffix(".zip")
            .substringBefore("-mc")
            .replace(Regex("[-_]\\d.*$"), "")
            .replace('_', ' ')
            .trim()
            .ifBlank { fileName }

    private class Meta(
        val name: String?,
        val version: String?,
        val description: String?,
        val icon: ByteArray?,
        val homepageUrl: String? = null,
        val license: String? = null,
        val authors: List<String> = emptyList(),
        val dependencies: List<String> = emptyList(),
    )

    private companion object {
        const val DISABLED = ".disabled"
        val TOML_DISPLAY_NAME = Regex("""displayName\s*=\s*["']([^"']*)["']""")
        val TOML_VERSION = Regex("""\bversion\s*=\s*["']([^"']*)["']""")
        val TOML_LOGO = Regex("""logoFile\s*=\s*["']([^"']*)["']""")
        val TOML_DISPLAY_URL = Regex("""displayURL\s*=\s*["']([^"']*)["']""")
        val TOML_LICENSE = Regex("""(?m)^\s*license\s*=\s*["']([^"']*)["']""")
        val TOML_AUTHORS = Regex("""authors\s*=\s*["']([^"']*)["']""")
        val ICON_CANDIDATES = listOf("icon.png", "pack.png", "logo.png", "icon.jpg", "logo.jpg")
        // Platform / loader ids that are always present and add no signal to a "requires" list.
        val PLATFORM_DEPS = setOf("minecraft", "java", "fabricloader", "quilt_loader", "quilted_fabric_api")
    }
}
