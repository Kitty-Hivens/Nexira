package hivens.launcher.instance

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModDependenciesTest {

    private fun maven(v: String, r: String) = VersionRanges.satisfies(v, r, RangeScheme.Maven)
    private fun fabric(v: String, r: String) = VersionRanges.satisfies(v, r, RangeScheme.Fabric)

    @Test
    fun `the reported crash reads as too old`() {
        // reeses_sodium_options asked for sodium 0.8.12 or above, and 0.6.13+mc1.21.1 was installed.
        assertEquals(false, maven("0.6.13+mc1.21.1", "[0.8.12,)"))
        assertEquals(true, maven("0.8.12+mc1.21.1", "[0.8.12,)"))
    }

    @Test
    fun `maven ranges as forge reads them`() {
        assertEquals(true, maven("1.5", "[1.0,2.0)"))
        assertEquals(false, maven("2.0", "[1.0,2.0)"))
        assertEquals(true, maven("2.0", "[1.0,2.0]"))
        assertEquals(false, maven("1.0", "(1.0,2.0]"))
        assertEquals(true, maven("1.9", "(,2.0]"))
        assertEquals(true, maven("1.0", "[1.0]"))
        assertEquals(false, maven("1.0.1", "[1.0]"))
        assertEquals(true, maven("3.1", "[1.0,2.0),[3.0,)"), "alternatives")
        assertEquals(true, maven("0.1", "1.0"), "a bare version is a soft preference")
        assertEquals(true, maven("0.1", "*"))
        assertEquals(true, maven("1.0.0", "[1,)"), "missing components are zero")
    }

    @Test
    fun `fabric predicates`() {
        assertEquals(true, fabric("0.8.12", ">=0.8.12"))
        assertEquals(false, fabric("0.8.11", ">=0.8.12"))
        assertEquals(true, fabric("1.2.9", "~1.2.3"))
        assertEquals(false, fabric("1.3.0", "~1.2.3"))
        assertEquals(true, fabric("1.9", "^1.2"))
        assertEquals(false, fabric("2.0", "^1.2"))
        assertEquals(true, fabric("1.20.4", "1.20.x"))
        assertEquals(false, fabric("1.21", "1.20.x"))
        assertEquals(true, fabric("1.5", ">=1.0 <2.0"))
        assertEquals(false, fabric("2.5", ">=1.0 <2.0"))
        assertEquals(true, fabric("9", "*"))
        assertEquals(false, fabric("1.0.1", "1.0.0"), "a bare version is exact")
        assertEquals(true, VersionRanges.satisfiesAny("1.21.1", listOf("1.21", "1.21.1"), RangeScheme.Fabric))
    }

    @Test
    fun `a pre-release is older than its release`() {
        assertTrue(VersionRanges.compare("1.0.0-beta.2", "1.0.0")!! < 0)
        assertTrue(VersionRanges.compare("1.0.0-beta.2", "1.0.0-beta.1")!! > 0)
    }

    @Test
    fun `a version that cannot be read says nothing`() {
        assertNull(maven("abc", "[1.0,)"))
        assertNull(maven("1.21.1-0.6.13", "[0.8.12,)"), "a game version glued on with a dash is not guessed at")
        assertNull(maven("1.0", "[1.0"), "a range in a shape this does not know")
    }

    private fun mod(
        file: String,
        provides: List<ProvidedMod>,
        requires: List<ModRequirement> = emptyList(),
        enabled: Boolean = true,
    ) = InstalledContent(
        kind = ContentKind.Mod, fileName = file, displayName = file, version = null, description = null,
        enabled = enabled, iconBytes = null, sizeBytes = 0, provides = provides, requires = requires,
    )

    @Test
    fun `issues name what is missing and what is too old, on the mod that needs it`() {
        val options = mod(
            "reeses.jar", listOf(ProvidedMod("reeses_sodium_options", "1.8")),
            listOf(
                ModRequirement("sodium", listOf("[0.8.12,)")),
                ModRequirement("cloth_config", emptyList()),
                ModRequirement("minecraft", listOf("[1.21.1]")),
                ModRequirement("neoforge", listOf("[99,)")),
            ),
        )
        val sodium = mod("sodium.jar", listOf(ProvidedMod("sodium", "0.6.13+mc1.21.1")))
        val issues = dependencyIssues(listOf(options, sodium), null).getValue(ContentRef(ContentKind.Mod, "reeses.jar"))
        assertEquals(2, issues.size, "platform ids are the loader's business: $issues")
        val wrong = issues.filterIsInstance<DependencyIssue.WrongVersion>().single()
        assertEquals("sodium", wrong.requirement.id)
        assertEquals(listOf("0.6.13+mc1.21.1"), wrong.installed)
        assertEquals("cloth_config", issues.filterIsInstance<DependencyIssue.Missing>().single().requirement.id)
    }

    @Test
    fun `a library nested in another mod counts, and a disabled one does not`() {
        val needs = mod("a.jar", listOf(ProvidedMod("a", "1")), listOf(ModRequirement("fabric-api-base", listOf(">=0.4"), RangeScheme.Fabric)))
        val api = mod("fabric-api.jar", listOf(ProvidedMod("fabric-api", "0.100"), ProvidedMod("fabric-api-base", "0.4.42")))
        assertTrue(dependencyIssues(listOf(needs, api), null).isEmpty())

        val off = mod("fabric-api.jar", api.provides, enabled = false)
        val issue = dependencyIssues(listOf(needs, off), null).getValue(ContentRef(ContentKind.Mod, "a.jar")).single()
        assertTrue(issue is DependencyIssue.Missing)
    }

    @Test
    fun `a provider whose version cannot be read is not called too old`() {
        val needs = mod("a.jar", emptyList(), listOf(ModRequirement("lib", listOf("[2.0,)"))))
        val lib = mod("lib.jar", listOf(ProvidedMod("lib", null)))
        assertTrue(dependencyIssues(listOf(needs, lib), null).isEmpty())
    }

    @Test
    fun `pre-release numbers compare as numbers`() {
        assertEquals(true, maven("1.0.0-beta.10", "[1.0.0-beta.9,)"))
        assertEquals(true, fabric("1.0.0-beta.10", ">=1.0.0-beta.9"))
        assertEquals(true, fabric("0.15.0-rc.2", ">=0.15.0-rc.1"))
    }

    @Test
    fun `a qualifier that is not a known pre-release word says nothing`() {
        assertNull(maven("2.1.0-forge", "[2.1.0,)"))
        assertNull(maven("1.20-Forge-4.0.6", "[4.0.0,)"), "a game version in front of the mod's own")
        assertEquals(true, maven("2.1.0-final", "[2.1.0,)"), "final is the release itself")
    }

    @Test
    fun `a trailing dash in a fabric bound reaches the pre-releases`() {
        assertEquals(true, fabric("0.6.0-beta.2", ">=0.6.0-"))
        assertEquals(false, fabric("1.21-rc.1", "<1.21-"))
    }

    @Test
    fun `a multi-loader jar is held to the manifest the pack's loader reads`() {
        val both = mod(
            "both.jar", listOf(ProvidedMod("both", "1")),
            listOf(
                ModRequirement("kotlinforforge", emptyList(), RangeScheme.Maven, "neoforge"),
                ModRequirement("fabric-language-kotlin", emptyList(), RangeScheme.Fabric, "fabric"),
            ),
        )
        val flk = mod("flk.jar", listOf(ProvidedMod("fabric-language-kotlin", "1.12")))
        assertTrue(dependencyIssues(listOf(both, flk), "fabric").isEmpty(), "a Fabric pack does not read the NeoForge manifest")
        val missing = dependencyIssues(listOf(both, flk), "neoforge").getValue(ContentRef(ContentKind.Mod, "both.jar")).single()
        assertEquals("kotlinforforge", missing.requirement.id)
        assertTrue(dependencyIssues(listOf(both), "vanilla").isEmpty(), "vanilla loads no mods to check")
    }

    @Test
    fun `quilt reads fabric manifests and neoforge reads forge ones`() {
        val fabricOnly = mod("f.jar", emptyList(), listOf(ModRequirement("lib", emptyList(), RangeScheme.Fabric, "fabric")))
        assertTrue(dependencyIssues(listOf(fabricOnly), "quilt").isNotEmpty())
        val forgeOnly = mod("g.jar", emptyList(), listOf(ModRequirement("lib", emptyList(), RangeScheme.Maven, "forge")))
        assertTrue(dependencyIssues(listOf(forgeOnly), "neoforge").isNotEmpty())
    }

    @Test
    fun `mixinextras is the loader's`() {
        val needs = mod("a.jar", emptyList(), listOf(ModRequirement("mixinextras", listOf(">=0.3.0"), RangeScheme.Fabric, "fabric")))
        assertTrue(dependencyIssues(listOf(needs), "fabric").isEmpty())
    }
}
