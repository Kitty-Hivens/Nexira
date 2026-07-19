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
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import hivens.core.io.IconProcessor
import hivens.core.io.SharedZip
import hivens.core.io.openSharedZip
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
class InstanceContentScanner(
    private val cache: ContentScanCache? = null,
    /**
     * Bounds extracted icons (a declared logo can be a multi-MB PNG rendered at
     * list-row size). Null in headless assemblies; the cache then falls back on
     * its own entry-size floor and oversized icons simply are not cached.
     */
    private val icons: IconProcessor? = null,
) {

    private val log = LoggerFactory.getLogger(InstanceContentScanner::class.java)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    suspend fun scan(instanceDir: Path): List<InstalledContent> = withContext(Dispatchers.IO) {
        val items = buildList {
            addAll(scanArchives(instanceDir.resolve("mods"), ContentKind.Mod))
            addAll(scanArchives(instanceDir.resolve("resourcepacks"), ContentKind.ResourcePack))
            addAll(scanArchives(instanceDir.resolve("shaderpacks"), ContentKind.ShaderPack))
        }.sortedBy { it.displayName.lowercase() }
        // Drop cache entries for files that disappeared since the last scan (a removed
        // mod). Edits overwrite in place (same key), so only deletions leave orphans.
        cache?.let { c ->
            val current = items.mapTo(HashSet()) {
                instanceDir.resolve(folderFor(it.kind)).resolve(it.fileName).normalize().toString()
            }
            c.retain(instanceDir.normalize().toString() + File.separator, current)
        }
        items
    }

    private fun folderFor(kind: ContentKind): String = when (kind) {
        ContentKind.Mod -> "mods"
        ContentKind.ResourcePack -> "resourcepacks"
        ContentKind.ShaderPack -> "shaderpacks"
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
        val mtime = Files.getLastModifiedTime(file).toMillis()
        // Key on the canonical (enabled) path so an optional-toggle rename keeps the
        // entry; validate by size+mtime so a real content change re-parses.
        val cacheKey = file.parent.resolve(fileName).normalize().toString()

        val meta: Meta? = when (val hit = cache?.lookup(cacheKey, size, mtime)) {
            null -> when (kind) {
                ContentKind.Mod -> readModMeta(file)
                ContentKind.ResourcePack -> readPackMeta(file)
                ContentKind.ShaderPack -> null
            }?.normalizeIcon().also { cache?.put(cacheKey, size, mtime, it?.toCached()) }
            else -> hit.meta?.toMeta()
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

    /**
     * TOML first when present: forge-only jars routinely ship a dummy
     * fabric.mod.json stub ("not a fabric mod") to scold a wrong-loader launch,
     * and reading it first put the stub's garbage on the row. A genuine
     * dual-loader jar describes the same mod in both files, so preferring the
     * TOML loses nothing there.
     */
    private fun readModMeta(file: Path): Meta? = openSharedZip(file).use { zip ->
        (zip.readEntry("META-INF/neoforge.mods.toml") ?: zip.readEntry("META-INF/mods.toml"))
            ?.let { return parseForgeToml(zip, it) }
        zip.readEntry("fabric.mod.json")?.let { return parseFabric(zip, it) }
        zip.readEntry("quilt.mod.json")?.let { return parseQuilt(zip, it) }
        null
    }

    private fun parseFabric(zip: SharedZip, bytes: ByteArray): Meta {
        val root = json.parseToJsonElement(bytes.decodeToString()).jsonObject
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

    private fun parseQuilt(zip: SharedZip, bytes: ByteArray): Meta {
        val loader = json.parseToJsonElement(bytes.decodeToString())
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
     * show. Values match by their OWN quote style, so an apostrophe inside a
     * double-quoted name ("Brewin' And Chewin'") no longer truncates the capture.
     * A `${file.jarVersion}` version placeholder resolves the way the loader
     * resolves it: from the manifest's Implementation-Version. Dependencies are
     * left out here: forge/neoforge `[[dependencies.<modid>]]` blocks need
     * section-aware parsing the flat line scan can't do reliably.
     */
    private fun parseForgeToml(zip: SharedZip, bytes: ByteArray): Meta {
        val text = bytes.decodeToString()
        val name = TOML_DISPLAY_NAME.tomlValue(text)
        val version = TOML_VERSION.tomlValue(text)
            ?.let { raw -> if (raw.startsWith($$"${")) manifestImplementationVersion(zip) else raw }
            ?.takeIf { it.isNotBlank() }
        val logo = TOML_LOGO.tomlValue(text)
        val homepage = TOML_DISPLAY_URL.tomlValue(text)?.takeIf { it.isNotBlank() }
        val license = TOML_LICENSE.tomlValue(text)?.takeIf { it.isNotBlank() }
        val authors = TOML_AUTHORS.tomlValue(text)
            ?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()
        return Meta(
            name, version, null, logo?.let { readEntryBytes(zip, it) },
            homepageUrl = homepage, license = license, authors = authors,
        )
    }

    /** The value the loader substitutes for `${file.jarVersion}`. */
    private fun manifestImplementationVersion(zip: SharedZip): String? =
        zip.readEntry("META-INF/MANIFEST.MF")?.decodeToString()
            ?.lineSequence()
            ?.firstOrNull { it.startsWith("Implementation-Version:") }
            ?.substringAfter(':')
            ?.trim()
            ?.ifBlank { null }

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
    private fun readPackMeta(file: Path): Meta? = openSharedZip(file).use { zip ->
        val description = zip.readEntry("pack.mcmeta")?.let { bytes ->
            runCatching {
                json.parseToJsonElement(bytes.decodeToString())
                    .jsonObject["pack"]?.jsonObject?.get("description")?.jsonPrimitive?.contentOrNull
            }.getOrNull()
        }
        Meta(null, null, description, readEntryBytes(zip, "pack.png"))
    }

    private fun readEntryBytes(zip: SharedZip, path: String): ByteArray? =
        zip.readEntry(path.removePrefix("/"))

    /**
     * Last-resort icon probe: an archive whose metadata declares NO icon may still
     * physically carry one (a bare `icon.png` at the root, or the Fabric-convention
     * `assets/<modid>/icon.png` left undeclared). Used as the fallback after a
     * remote (Modrinth) lookup misses, so such a mod shows its own art instead of a
     * letter. Returns null when the archive truly has no recognizable icon.
     */
    fun probeJarIcon(file: Path): ByteArray? = runCatching {
        openSharedZip(file).use { zip ->
            for (name in ICON_CANDIDATES) zip.readEntry(name)?.let { return@use it }
            val nested = zip.entryNames().firstOrNull { it.startsWith("assets/") && it.endsWith("/icon.png") }
            nested?.let { zip.readEntry(it) }
        }
    }.getOrNull()?.let { bytes -> icons?.process(bytes) ?: bytes }

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

    /** Runs the icon through the bound [icons] processor; identity when none is bound or there is no icon. */
    private fun Meta.normalizeIcon(): Meta {
        val processor = icons ?: return this
        val original = icon ?: return this
        val processed = processor.process(original)
        return if (processed === original) this
        else Meta(name, version, description, processed, homepageUrl, license, authors, dependencies)
    }

    private fun Meta.toCached() =
        CachedMeta(name, version, description, icon, homepageUrl, license, authors, dependencies)

    private fun CachedMeta.toMeta() =
        Meta(name, version, description, icon, homepageUrl, license, authors, dependencies)

    private companion object {
        const val DISABLED = ".disabled"

        /** `key = "value"` / `key = 'value'`, each quote style closed by its own kind. */
        fun tomlString(prefix: String) = Regex("""$prefix\s*=\s*(?:"([^"]*)"|'([^']*)')""")

        /** The captured value of a [tomlString] match: whichever quote group matched. */
        fun Regex.tomlValue(text: String): String? =
            find(text)?.let { m -> m.groups[1]?.value ?: m.groups[2]?.value }

        val TOML_DISPLAY_NAME = tomlString("displayName")
        val TOML_VERSION = tomlString("""\bversion""")
        val TOML_LOGO = tomlString("logoFile")
        val TOML_DISPLAY_URL = tomlString("displayURL")
        val TOML_LICENSE = tomlString("""(?m)^\s*license""")
        val TOML_AUTHORS = tomlString("authors")
        val ICON_CANDIDATES = listOf("icon.png", "pack.png", "logo.png", "icon.jpg", "logo.jpg")
        // Platform / loader ids that are always present and add no signal to a "requires" list.
        val PLATFORM_DEPS = setOf("minecraft", "java", "fabricloader", "quilt_loader", "quilted_fabric_api")
    }
}
