package hivens.launcher.instance

import kotlinx.serialization.Serializable

/** A mod id an archive provides, at the version it provides it. */
@Serializable
data class ProvidedMod(val id: String, val version: String? = null)

/** How a requirement writes its versions: a Maven range (Forge, NeoForge) or a Fabric-style predicate (Fabric, Quilt). */
@Serializable
enum class RangeScheme { Maven, Fabric }

/**
 * A mod id an archive cannot run without, and the versions it accepts.
 *
 * [ranges] are alternatives: any one satisfied is enough, and none at all means
 * any version will do. Only hard requirements are kept. An optional dependency,
 * an incompatibility and a server-side one are not this archive's demand on a
 * client launch.
 */
@Serializable
data class ModRequirement(
    val id: String,
    val ranges: List<String> = emptyList(),
    val scheme: RangeScheme = RangeScheme.Maven,
)

/** What is wrong with one requirement of one installed mod. */
sealed interface DependencyIssue {
    val requirement: ModRequirement

    /** Nothing installed and enabled provides the id. */
    data class Missing(override val requirement: ModRequirement) : DependencyIssue

    /** It is provided, at versions none of which the requirement accepts. */
    data class WrongVersion(override val requirement: ModRequirement, val installed: List<String>) : DependencyIssue
}

/**
 * What each enabled mod in [items] needs and does not have, keyed by the mod
 * that needs it. A mod with nothing missing is absent.
 *
 * Reported, never acted on: a pack is the player's to break. A version that
 * cannot be read, or a range in a shape this does not know, says nothing rather
 * than something wrong, because a false alarm before every launch teaches a
 * player to ignore the real one. Platform ids (the game, the loader, Java) are
 * not mods and are left to the loader.
 *
 * A requirement is met by any enabled archive providing the id, nested jars
 * included, so a library bundled inside another mod counts as installed.
 */
fun dependencyIssues(items: List<InstalledContent>): Map<ContentRef, List<DependencyIssue>> {
    val mods = items.filter { it.kind == ContentKind.Mod && it.enabled }
    val providers = HashMap<String, MutableList<String?>>()
    for (mod in mods) for (p in mod.provides) providers.getOrPut(p.id.lowercase()) { mutableListOf() } += p.version
    val out = LinkedHashMap<ContentRef, List<DependencyIssue>>()
    for (mod in mods) {
        val issues = mod.requires.mapNotNull { req ->
            val id = req.id.lowercase()
            if (id in PLATFORM_IDS) return@mapNotNull null
            val versions = providers[id] ?: return@mapNotNull DependencyIssue.Missing(req)
            if (req.ranges.isEmpty()) return@mapNotNull null
            val verdicts = versions.map { v -> v?.let { VersionRanges.satisfiesAny(it, req.ranges, req.scheme) } }
            // Only a certain no from every provider is a mismatch.
            if (verdicts.all { it == false }) DependencyIssue.WrongVersion(req, versions.filterNotNull()) else null
        }
        if (issues.isNotEmpty()) out[ContentRef(mod.kind, mod.fileName)] = issues
    }
    return out
}

/** Ids every loader satisfies itself, which no archive in `mods/` provides. */
private val PLATFORM_IDS = setOf(
    "minecraft", "java", "forge", "neoforge", "fml", "javafml", "lowcodefml", "mcp",
    "fabricloader", "fabric-loader", "quilt_loader", "quilt-loader",
)

/**
 * Version comparison and range matching, lenient by design: every answer is
 * true, false, or null for "cannot tell".
 *
 * Mod versions are not one format. A Fabric mod writes semver, a Forge mod
 * whatever it likes, and plenty carry the game version in them
 * (`0.6.13+mc1.21.1`, `1.21.1-0.6.13`). Build metadata after `+` is ignored, as
 * semver says. A pre-release that itself starts with a digit is most likely a
 * second version glued on with a dash, and is not guessed at.
 */
object VersionRanges {

    fun satisfiesAny(version: String, ranges: List<String>, scheme: RangeScheme): Boolean? {
        val verdicts = ranges.map { satisfies(version, it, scheme) }
        return when {
            verdicts.any { it == true } -> true
            verdicts.all { it == false } -> false
            else -> null
        }
    }

    fun satisfies(version: String, range: String, scheme: RangeScheme): Boolean? = when (scheme) {
        RangeScheme.Maven -> maven(version, range.trim())
        RangeScheme.Fabric -> fabric(version, range.trim())
    }

    /**
     * Negative, zero or positive as [a] is older, equal or newer than [b], or null
     * when either cannot be read.
     */
    fun compare(a: String, b: String): Int? {
        val va = parse(a) ?: return null
        val vb = parse(b) ?: return null
        val size = maxOf(va.release.size, vb.release.size)
        for (i in 0 until size) {
            val c = va.release.getOrElse(i) { 0 }.compareTo(vb.release.getOrElse(i) { 0 })
            if (c != 0) return c
        }
        return when {
            va.pre == null && vb.pre == null -> 0
            va.pre == null -> 1
            vb.pre == null -> -1
            else -> va.pre.compareTo(vb.pre)
        }
    }

    private class Parsed(val release: List<Int>, val pre: String?)

    private fun parse(v: String): Parsed? {
        val core = v.trim().removePrefix("v").substringBefore('+')
        if (core.isEmpty()) return null
        val release = core.substringBefore('-')
        val pre = core.substringAfter('-', "").ifEmpty { null }
        if (pre != null && pre.first().isDigit()) return null
        val parts = release.split('.').map { it.toIntOrNull() ?: return null }
        return Parsed(parts, pre)
    }

    /**
     * A Maven range as Forge and NeoForge read it: `[1.0,2.0)`, `[0.8.12,)`,
     * `(,2.0]`, `[1.0]` for exactly one, several joined by commas as
     * alternatives. A bare version is a soft preference that accepts anything,
     * and so is `*` or a blank.
     */
    private fun maven(version: String, range: String): Boolean? {
        if (range.isEmpty() || range == "*") return true
        if (range.first() != '[' && range.first() != '(') return true
        val parts = MAVEN_RANGE.findAll(range).toList()
        if (parts.isEmpty()) return null
        val verdicts = parts.map { m ->
            val open = m.groupValues[1]
            val low = m.groupValues[2].trim()
            val hasComma = m.groupValues[3].isNotEmpty()
            val high = m.groupValues[4].trim()
            val close = m.groupValues[5]
            if (!hasComma) {
                compare(version, low)?.let { it == 0 }
            } else {
                val lowOk = if (low.isEmpty()) true else compare(version, low)?.let { if (open == "[") it >= 0 else it > 0 }
                val highOk = if (high.isEmpty()) true else compare(version, high)?.let { if (close == "]") it <= 0 else it < 0 }
                if (lowOk == null || highOk == null) null else lowOk && highOk
            }
        }
        return when {
            verdicts.any { it == true } -> true
            verdicts.all { it == false } -> false
            else -> null
        }
    }

    /**
     * A Fabric version predicate: space-separated terms that must all hold, each
     * `*`, a comparison (`>=`, `>`, `<=`, `<`, `=`), a tilde (same minor) or caret
     * (same major) range, or a bare version, which means exactly that one and may
     * end in `.x` to mean any.
     */
    private fun fabric(version: String, predicate: String): Boolean? {
        if (predicate.isEmpty()) return true
        val verdicts = predicate.split(Regex("\\s+")).map { term -> fabricTerm(version, term) }
        return when {
            verdicts.any { it == false } -> false
            verdicts.all { it == true } -> true
            else -> null
        }
    }

    private fun fabricTerm(version: String, term: String): Boolean? {
        if (term == "*") return true
        val op = listOf(">=", "<=", ">", "<", "=", "~", "^").firstOrNull { term.startsWith(it) }
        val target = if (op == null) term else term.removePrefix(op)
        if (target.contains('x') || target.contains('X') || target.contains('*')) {
            if (op != null && op != "=") return null
            val prefix = target.substringBefore(".x").substringBefore(".X").substringBefore(".*")
            val have = parse(version) ?: return null
            val want = parse(prefix) ?: return null
            return have.release.take(want.release.size) == want.release
        }
        val c = compare(version, target) ?: return null
        return when (op) {
            ">=" -> c >= 0
            "<=" -> c <= 0
            ">" -> c > 0
            "<" -> c < 0
            "=", null -> c == 0
            "~" -> if (c < 0) false else below(version, target, keep = 2)
            "^" -> if (c < 0) false else below(version, target, keep = 1)
            else -> null
        }
    }

    /**
     * Whether [version] is below the next step of [target]'s first [keep]
     * components: `~1.2.3` stops before 1.3, `^1.2.3` before 2. A tilde over a bare
     * major (`~1`) steps the major, as Fabric does.
     */
    private fun below(version: String, target: String, keep: Int): Boolean? {
        val t = parse(target) ?: return null
        val kept = t.release.take(keep)
        val ceiling = kept.toMutableList().also { it[it.lastIndex] = it.last() + 1 }
        return compare(version, ceiling.joinToString("."))?.let { it < 0 }
    }

    private val MAVEN_RANGE = Regex("""([\[(])([^,\])]*)(,?)([^\])]*)([\])])""")
}
