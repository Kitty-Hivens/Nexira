package hivens.core.data

import hivens.core.api.dto.smrt.SmrtAssetEntry
import hivens.core.api.dto.smrt.SmrtDisplay
import hivens.core.api.dto.smrt.SmrtJava
import hivens.core.api.dto.smrt.SmrtLoader
import hivens.core.api.dto.smrt.SmrtMinecraft
import hivens.core.api.dto.smrt.SmrtModEntry
import hivens.core.api.dto.smrt.SmrtPackManifest
import hivens.core.api.dto.smrt.SmrtRequirement
import hivens.core.api.dto.smrt.SmrtSource
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OptionalContentRulesTest {

    private fun mod(
        filename: String,
        required: Boolean = true,
        defaultEnabled: Boolean = true,
        incompatibleWith: List<String> = emptyList(),
        requires: List<String> = emptyList(),
        optionalRequires: List<String> = emptyList(),
        role: String? = null,
        slug: String? = null,
        projectId: String? = null,
    ): SmrtModEntry {
        val hasDisplay = incompatibleWith.isNotEmpty() || requires.isNotEmpty() ||
            optionalRequires.isNotEmpty() || role != null
        return SmrtModEntry(
            filename = filename,
            sha1 = "x",
            sizeBytes = 1,
            required = required,
            defaultEnabled = defaultEnabled,
            slug = slug,
            source = if (projectId != null) {
                SmrtSource.Modrinth(projectId = projectId, versionId = "$projectId-v1")
            } else {
                SmrtSource.SmrtStatic("https://example/$filename")
            },
            display = if (!hasDisplay) null else SmrtDisplay(
                incompatibleWith = incompatibleWith,
                role = role,
                requires = requires.map { SmrtRequirement(it) } +
                    optionalRequires.map { SmrtRequirement(it, optional = true) },
            ),
        )
    }

    private val mods = listOf(
        mod("required.jar"),
        mod("foamfix.jar", required = false, defaultEnabled = false, incompatibleWith = listOf("mixinbooter.jar")),
        mod("mixinbooter.jar", required = false, defaultEnabled = true),
    )

    @Test
    fun `defaultToggles lists only optionals at their default_enabled`() {
        val toggles = OptionalContentRules.defaultToggles(mods)
        assertEquals(2, toggles.size)
        assertFalse(toggles.any { it.entryId == "required.jar" }, "required mods are never toggles")
        assertEquals(false, toggles.first { it.entryId == "foamfix.jar" }.enabled)
        assertEquals(true, toggles.first { it.entryId == "mixinbooter.jar" }.enabled)
    }

    @Test
    fun `enabledState forces required on and uses toggle-or-default for optionals`() {
        val state = OptionalContentRules.enabledState(mods, listOf(ContentToggle("foamfix.jar", true)))
        assertEquals(true, state["required.jar"], "required always on")
        assertEquals(true, state["foamfix.jar"], "user toggle wins over default")
        assertEquals(true, state["mixinbooter.jar"], "untouched optional uses default_enabled, conflict or not")
    }

    @Test
    fun `a saved conflicting pair is reported on both sides and left as it is`() {
        val state = OptionalContentRules.enabledState(mods, listOf(ContentToggle("foamfix.jar", true)))
        val problems = OptionalContentRules.problems(mods, state)
        assertEquals(listOf("mixinbooter.jar"), problems["foamfix.jar"]?.map { it.other.filename })
        assertEquals(listOf("foamfix.jar"), problems["mixinbooter.jar"]?.map { it.other.filename })
        assertTrue(problems["foamfix.jar"]!!.single() is OptionalContentRules.Problem.ConflictsWith)
        assertNull(problems["required.jar"])
    }

    @Test
    fun `enabling an optional beside a required mod it conflicts with is allowed and reported`() {
        val m = listOf(
            mod("core.jar", role = "renderer"),
            mod("alt.jar", required = false, defaultEnabled = false, role = "renderer"),
        )
        val after = OptionalContentRules.applyToggle(m, mapOf("core.jar" to true, "alt.jar" to false), "alt.jar", true)
        assertEquals(true, after["alt.jar"], "the player asked for it")
        assertEquals(true, after["core.jar"], "a required mod is never switched off")
        assertTrue(OptionalContentRules.problems(m, after)["alt.jar"]!!.single() is OptionalContentRules.Problem.ConflictsWith)
    }

    @Test
    fun `turning off a library something still needs is allowed and reported on the consumer`() {
        val m = listOf(
            mod("consumer.jar", required = false, defaultEnabled = true, requires = listOf("lib.jar")),
            mod("lib.jar", required = false, defaultEnabled = true),
        )
        val after = OptionalContentRules.applyToggle(m, mapOf("consumer.jar" to true, "lib.jar" to true), "lib.jar", false)
        assertEquals(false, after["lib.jar"])
        assertEquals(true, after["consumer.jar"], "nothing is turned off behind the player")
        val problem = OptionalContentRules.problems(m, after)["consumer.jar"]!!.single()
        assertTrue(problem is OptionalContentRules.Problem.NeedsDisabled && problem.other.filename == "lib.jar")
    }

    @Test
    fun `a required mod whose optional library is off is reported`() {
        val m = listOf(
            mod("core.jar", requires = listOf("lib.jar")),
            mod("lib.jar", required = false, defaultEnabled = false),
        )
        val state = OptionalContentRules.enabledState(m, emptyList())
        assertEquals(false, state["lib.jar"])
        assertTrue(OptionalContentRules.problems(m, state)["core.jar"]!!.single() is OptionalContentRules.Problem.NeedsDisabled)
    }

    @Test
    fun `the library that is off says who needs it`() {
        val m = listOf(
            mod("core.jar", requires = listOf("lib.jar")),
            mod("lib.jar", required = false, defaultEnabled = false),
        )
        val problem = OptionalContentRules.problems(m, OptionalContentRules.enabledState(m, emptyList()))["lib.jar"]!!.single()
        assertTrue(problem is OptionalContentRules.Problem.NeededBy && problem.other.filename == "core.jar")
    }

    @Test
    fun `a key two entries share names both of them`() {
        val m = listOf(
            mod("viewer.jar", required = false, defaultEnabled = true, incompatibleWith = listOf("modrinth:SHARED")),
            mod("a-1.jar", required = false, defaultEnabled = true, projectId = "SHARED"),
            mod("b-1.jar", required = false, defaultEnabled = true, projectId = "SHARED"),
        )
        val problems = OptionalContentRules.problems(m, OptionalContentRules.enabledState(m, emptyList()))
        assertEquals(setOf("a-1.jar", "b-1.jar"), problems["viewer.jar"]!!.map { it.other.filename }.toSet())
    }

    @Test
    fun `enabling a mod never switches off what it is turning on`() {
        // A contradictory manifest: the consumer requires a library it also declares incompatible.
        val m = listOf(
            mod("consumer.jar", required = false, defaultEnabled = false, requires = listOf("lib.jar"), incompatibleWith = listOf("lib.jar")),
            mod("lib.jar", required = false, defaultEnabled = false),
        )
        val after = OptionalContentRules.applyToggle(m, mapOf("consumer.jar" to false, "lib.jar" to false), "consumer.jar", true)
        assertEquals(true, after["consumer.jar"])
        assertEquals(true, after["lib.jar"])
        assertTrue(OptionalContentRules.problems(m, after)["consumer.jar"]!!.isNotEmpty(), "the contradiction is shown instead")
    }

    @Test
    fun `a selection the rules can keep has no problems`() {
        val m = listOf(
            mod("consumer.jar", required = false, defaultEnabled = true, requires = listOf("lib.jar")),
            mod("lib.jar", required = false, defaultEnabled = true),
            mod("jei.jar", required = false, defaultEnabled = true, role = "recipe_viewer"),
            mod("rei.jar", required = false, defaultEnabled = false, role = "recipe_viewer"),
        )
        assertTrue(OptionalContentRules.problems(m, OptionalContentRules.enabledState(m, emptyList())).isEmpty())
    }

    @Test
    fun `incompatible_with written as a stable key survives the filename changing`() {
        val m = listOf(
            mod("foamfix-0.11.jar", required = false, defaultEnabled = true, incompatibleWith = listOf("modrinth:MIXB")),
            mod("mixinbooter-9.4.jar", required = false, defaultEnabled = true, projectId = "MIXB"),
        )
        assertTrue(OptionalContentRules.conflicts(m, "foamfix-0.11.jar", "mixinbooter-9.4.jar"))
    }

    @Test
    fun `togglesFrom emits one keyed entry per optional, omitting required`() {
        val state = mapOf("required.jar" to true, "foamfix.jar" to true, "mixinbooter.jar" to false)
        val toggles = OptionalContentRules.togglesFrom(mods, state)
        assertEquals(2, toggles.size)
        assertFalse(toggles.any { it.entryId == "required.jar" }, "required mods never become toggles")
        assertEquals(true, toggles.first { it.entryId == "foamfix.jar" }.enabled)
        assertEquals(false, toggles.first { it.entryId == "mixinbooter.jar" }.enabled)
    }

    @Test
    fun `togglesFrom uses stableKey and falls back to default_enabled for absent entries`() {
        val m = listOf(mod("jei-1.0.jar", required = false, defaultEnabled = true, projectId = "P7dR8mSH"))
        val toggles = OptionalContentRules.togglesFrom(m, emptyMap())
        assertEquals(1, toggles.size)
        assertEquals("modrinth:P7dR8mSH", toggles.first().entryId)
        assertEquals(true, toggles.first().enabled, "absent from the map -> manifest default_enabled")
    }

    @Test
    fun `enabledState then togglesFrom round-trips the optional state`() {
        val saved = listOf(ContentToggle("foamfix.jar", true), ContentToggle("mixinbooter.jar", false))
        val state = OptionalContentRules.enabledState(mods, saved)
        val rebuilt = OptionalContentRules.togglesFrom(mods, state).associate { it.entryId to it.enabled }
        assertEquals(true, rebuilt["foamfix.jar"])
        assertEquals(false, rebuilt["mixinbooter.jar"])
    }

    @Test
    fun `conflicts is mutual even when only one side declares it`() {
        assertTrue(OptionalContentRules.conflicts(mods, "foamfix.jar", "mixinbooter.jar"))
        assertTrue(OptionalContentRules.conflicts(mods, "mixinbooter.jar", "foamfix.jar"))
        assertFalse(OptionalContentRules.conflicts(mods, "foamfix.jar", "foamfix.jar"))
        assertFalse(OptionalContentRules.conflicts(mods, "required.jar", "mixinbooter.jar"))
    }

    @Test
    fun `applyToggle enabling disables conflicts and disabling does not cascade`() {
        val current = mapOf("foamfix.jar" to false, "mixinbooter.jar" to true)

        val afterEnable = OptionalContentRules.applyToggle(mods, current, "foamfix.jar", true)
        assertEquals(true, afterEnable["foamfix.jar"])
        assertEquals(false, afterEnable["mixinbooter.jar"], "enabling foamfix disables the incompatible mixinbooter")

        val afterDisable = OptionalContentRules.applyToggle(mods, afterEnable, "foamfix.jar", false)
        assertEquals(false, afterDisable["foamfix.jar"])
        assertEquals(false, afterDisable["mixinbooter.jar"], "disabling foamfix must not silently re-enable mixinbooter")
    }

    @Test
    fun `applyToggle enabling a mod pulls its required deps on, transitively`() {
        // A library can ship optional + default-off and follow its consumer on,
        // instead of being flat-required: consumer -> libA -> libB.
        val deps = listOf(
            mod("consumer.jar", required = false, defaultEnabled = false, requires = listOf("libA.jar")),
            mod("libA.jar", required = false, defaultEnabled = false, requires = listOf("libB.jar")),
            mod("libB.jar", required = false, defaultEnabled = false),
        )
        val current = mapOf("consumer.jar" to false, "libA.jar" to false, "libB.jar" to false)
        val after = OptionalContentRules.applyToggle(deps, current, "consumer.jar", true)
        assertEquals(true, after["consumer.jar"])
        assertEquals(true, after["libA.jar"], "direct required dep follows on")
        assertEquals(true, after["libB.jar"], "transitive required dep follows on")
    }

    @Test
    fun `applyToggle does not follow optional (soft) requires`() {
        val deps = listOf(
            mod("consumer.jar", required = false, defaultEnabled = false, optionalRequires = listOf("soft.jar")),
            mod("soft.jar", required = false, defaultEnabled = false),
        )
        val after = OptionalContentRules.applyToggle(deps, mapOf("consumer.jar" to false, "soft.jar" to false), "consumer.jar", true)
        assertEquals(true, after["consumer.jar"])
        assertEquals(false, after["soft.jar"], "a soft (optional) requires must not be force-enabled")
    }

    @Test
    fun `applyToggle enabling one role member disables the others in that role`() {
        val viewers = listOf(
            mod("jei.jar", required = false, defaultEnabled = true, role = "recipe_viewer"),
            mod("rei.jar", required = false, defaultEnabled = false, role = "recipe_viewer"),
            mod("unrelated.jar", required = false, defaultEnabled = true),
        )
        val current = mapOf("jei.jar" to true, "rei.jar" to false, "unrelated.jar" to true)
        val after = OptionalContentRules.applyToggle(viewers, current, "rei.jar", true)
        assertEquals(true, after["rei.jar"])
        assertEquals(false, after["jei.jar"], "one active per interchangeable role")
        assertEquals(true, after["unrelated.jar"], "a different role is untouched")
    }

    @Test
    fun `applyToggle survives a requires cycle`() {
        // A bad manifest with a -> b -> a must terminate, not loop.
        val cyclic = listOf(
            mod("a.jar", required = false, defaultEnabled = false, requires = listOf("b.jar")),
            mod("b.jar", required = false, defaultEnabled = false, requires = listOf("a.jar")),
        )
        val after = OptionalContentRules.applyToggle(cyclic, mapOf("a.jar" to false, "b.jar" to false), "a.jar", true)
        assertEquals(true, after["a.jar"])
        assertEquals(true, after["b.jar"])
    }

    @Test
    fun `stableKey prefers slug then modrinth project id then filename`() {
        assertEquals("recipe-viewer", mod("jei-1.0.jar", slug = "recipe-viewer").stableKey)
        assertEquals("modrinth:P7dR8mSH", mod("jei-1.0.jar", projectId = "P7dR8mSH").stableKey)
        assertEquals("jei-1.0.jar", mod("jei-1.0.jar").stableKey)
        // an explicit slug wins over the Modrinth fallback
        assertEquals("recipe-viewer", mod("jei-1.0.jar", slug = "recipe-viewer", projectId = "P7dR8mSH").stableKey)
    }

    @Test
    fun `defaultToggles key on stableKey not filename`() {
        val toggles = OptionalContentRules.defaultToggles(
            listOf(mod("jei-1.0.jar", required = false, defaultEnabled = false, projectId = "P7dR8mSH")),
        )
        assertEquals(1, toggles.size)
        assertEquals("modrinth:P7dR8mSH", toggles.first().entryId)
    }

    @Test
    fun `a toggle survives a pack-version bump when the stable key is unchanged`() {
        // The exact #339 failure: user disables a default-on Modrinth optional,
        // the pack updates (filename carries the new version), the project id is
        // unchanged -- the choice must survive instead of reverting to default.
        val v1 = mod("jei-1.12.2-4.16.1.301.jar", required = false, defaultEnabled = true, projectId = "P7dR8mSH")
        val saved = listOf(ContentToggle(v1.stableKey, enabled = false))

        val v2 = mod("jei-1.12.2-4.17.0.jar", required = false, defaultEnabled = true, projectId = "P7dR8mSH")
        val state = OptionalContentRules.enabledState(listOf(v2), saved)

        assertEquals(false, state["jei-1.12.2-4.17.0.jar"], "off-toggle survives the version bump via stableKey")
    }

    @Test
    fun `a legacy filename-keyed toggle is still honored`() {
        // State persisted before #339 keyed by filename for a Modrinth mod; the
        // fallback lookup must still apply it (until rewritten by the next toggle).
        val m = mod("jei-1.12.2-4.16.1.301.jar", required = false, defaultEnabled = true, projectId = "P7dR8mSH")
        val legacy = listOf(ContentToggle("jei-1.12.2-4.16.1.301.jar", enabled = false))

        val state = OptionalContentRules.enabledState(listOf(m), legacy)

        assertEquals(false, state["jei-1.12.2-4.16.1.301.jar"], "legacy filename-keyed toggle still applies")
    }

    // ── Assets the player may switch off ────────────────────────────────────

    private fun asset(dest: String, required: Boolean = false, projectId: String? = null) = SmrtAssetEntry(
        dest = dest,
        sha1 = "x",
        sizeBytes = 1,
        required = required,
        source = if (projectId != null) SmrtSource.Modrinth(projectId, "$projectId-v1") else SmrtSource.SmrtStatic("https://example/$dest"),
    )

    private fun manifest(assets: List<SmrtAssetEntry>) = SmrtPackManifest(
        schemaVersion = 2,
        packId = "p",
        packVersion = "1",
        generatedAt = "now",
        minecraft = SmrtMinecraft("1.20.1"),
        loader = SmrtLoader("fabric", "0.16"),
        java = SmrtJava(21),
        mods = mods,
        assets = assets,
    )

    @Test
    fun `only an optional resource or shader pack can be switched off`() {
        assertTrue(asset("resourcepacks/a.zip").toggleable)
        assertTrue(asset("shaderpacks/b.zip").toggleable)
        assertFalse(asset("resourcepacks/c.zip", required = true).toggleable, "the curator made it part of the pack")
        assertFalse(asset("config/d.json").toggleable, "the game reads a config by its path and has no off for it")
        assertFalse(asset("resourcepacks/Unpacked/pack.mcmeta").toggleable, "a file of a pack shipped unpacked, not a pack")
        assertFalse(asset("shaderpacks/BSL.zip.txt").toggleable, "a shader's settings, which an off name would only lose")
    }

    @Test
    fun `an asset nobody switched off is on, and a switched one is read by its key`() {
        val faithful = asset("resourcepacks/faithful-1.zip", projectId = "faith")
        val state = OptionalContentRules.assetState(
            listOf(faithful, asset("resourcepacks/other.zip"), asset("servers.dat", required = true)),
            listOf(ContentToggle(faithful.stableKey, false)),
        )
        assertEquals(mapOf("resourcepacks/faithful-1.zip" to false, "resourcepacks/other.zip" to true, "servers.dat" to true), state)
    }

    @Test
    fun `an asset's choice follows its project to a new build, and a mod of the same project is another choice`() {
        val old = asset("resourcepacks/faithful-1.zip", projectId = "faith")
        val next = asset("resourcepacks/faithful-2.zip", projectId = "faith")
        assertEquals(old.stableKey, next.stableKey)
        assertEquals(false, OptionalContentRules.assetState(listOf(next), listOf(ContentToggle(old.stableKey, false)))[next.dest])
        assertTrue(old.stableKey != mod("faith.jar", projectId = "faith").stableKey)
    }

    @Test
    fun `the whole choice keeps the mods and the assets, and a fresh install seeds both`() {
        val rp = asset("resourcepacks/a.zip")
        val m = manifest(listOf(rp, asset("servers.dat", required = true)))

        val toggles = OptionalContentRules.togglesFrom(m, mapOf("foamfix.jar" to true), mapOf(rp.dest to false))
        assertEquals(false, toggles.single { it.entryId == rp.stableKey }.enabled)
        assertEquals(true, toggles.single { it.entryId == "foamfix.jar" }.enabled)
        assertEquals(toggles.toSet(), OptionalContentRules.carried(m, toggles).toSet(), "carrying the choice onto the same build changes nothing")

        val seeded = OptionalContentRules.defaultToggles(m)
        assertEquals(true, seeded.single { it.entryId == rp.stableKey }.enabled)
        assertFalse(seeded.any { it.entryId.endsWith("servers.dat") }, "a required asset is no choice")
    }

    /** The path carries the version, so a choice keyed on it was lost at the next build. */
    @Test
    fun `an asset's choice follows its host's project across builds, and one kept by path is still read`() {
        fun cf(dest: String) = SmrtAssetEntry(dest = dest, sha1 = "x", sizeBytes = 1, required = false, source = SmrtSource.CurseForge(42, 1))
        val old = cf("resourcepacks/Faithful-1.2.zip")
        val next = cf("resourcepacks/Faithful-1.3.zip")

        assertEquals(old.stableKey, next.stableKey)
        assertEquals(false, OptionalContentRules.assetState(listOf(next), listOf(ContentToggle(old.stableKey, false)))[next.dest])
        assertEquals(
            false,
            OptionalContentRules.assetState(listOf(old), listOf(ContentToggle(old.pathKey, false)))[old.dest],
            "a choice written before the key followed the host",
        )
    }

    /** One key for both, and switching either moved both in the record. */
    @Test
    fun `two switchable assets of one project are two choices`() {
        val day = asset("resourcepacks/Day.zip", projectId = "duo")
        val night = asset("resourcepacks/Night.zip", projectId = "duo")
        val m = manifest(listOf(day, night))

        val toggles = OptionalContentRules.togglesFrom(m, emptyMap(), mapOf(day.dest to false, night.dest to true))
        val state = OptionalContentRules.assetState(m.assets, toggles)

        assertEquals(2, toggles.count { it.entryId.startsWith(SmrtAssetEntry.ASSET_KEY_PREFIX) })
        assertEquals(mapOf(day.dest to false, night.dest to true), state)
    }

    /**
     * A resource pack the player switched off by hand while the pack still shipped
     * it as plain content: off on disk, and no choice written down. Read as on, the
     * next relabel put it back in the game.
     */
    @Test
    fun `an optional asset with no choice recorded is read off the disk, and a recorded choice wins`() {
        val handOff = asset("resourcepacks/hand-off.zip")
        val chosen = asset("resourcepacks/chosen.zip")
        val absent = asset("resourcepacks/absent.zip")
        val required = asset("resourcepacks/required.zip", required = true)
        val dir = Files.createTempDirectory("assets-on-disk")
        try {
            Files.createDirectories(dir.resolve("resourcepacks"))
            Files.writeString(dir.resolve("resourcepacks/hand-off.zip.disabled"), "x")
            Files.writeString(dir.resolve("resourcepacks/chosen.zip.disabled"), "x")
            Files.writeString(dir.resolve("resourcepacks/required.zip.disabled"), "x")

            val state = OptionalContentRules.assetState(
                listOf(handOff, chosen, absent, required),
                listOf(ContentToggle(chosen.stableKey, true)),
                OptionalContentRules.placedIn(dir),
            )

            assertEquals(false, state[handOff.dest], "off on disk and nothing recorded")
            assertEquals(true, state[chosen.dest], "the recorded choice, not the disk")
            assertEquals(true, state[absent.dest], "neither name on disk falls back to on")
            assertEquals(true, state[required.dest], "a required asset has no off")
            assertEquals(
                false,
                OptionalContentRules.carried(manifest(listOf(handOff)), emptyList(), OptionalContentRules.placedIn(dir))
                    .single { it.entryId == handOff.stableKey }.enabled,
                "the first rewrite of the record writes the disk's answer down",
            )
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
