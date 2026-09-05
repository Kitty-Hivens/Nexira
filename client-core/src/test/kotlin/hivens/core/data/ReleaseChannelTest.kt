package hivens.core.data

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReleaseChannelTest {

    // The launcher reads settings.json with this leniency (see the DI Json).
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `classify maps a tag's prerelease suffix to a channel`() {
        assertEquals(ReleaseChannel.Release, ReleaseChannel.classify("2.3.4"))
        assertEquals(ReleaseChannel.Beta,    ReleaseChannel.classify("2.3.4-beta1"))
        assertEquals(ReleaseChannel.Beta,    ReleaseChannel.classify("2.3.4-rc2"))
        assertEquals(ReleaseChannel.Alpha,   ReleaseChannel.classify("2.3.4-alpha"))
        assertEquals(ReleaseChannel.Alpha,   ReleaseChannel.classify("2.3.4-Alpha.3"))
        // CI nightly: `-nightly<commit-count>`, no dot, matching the -beta5 style.
        assertEquals(ReleaseChannel.Nightly, ReleaseChannel.classify("2.5.0-nightly1218"))
    }

    @Test
    fun `only a suffix starting with nightly is a nightly`() {
        // The release workflow exempts nightlies from its review gate and must
        // read a tag the same way this does. It used to test the whole tag for
        // the substring, so these three walked past the gate while classify
        // still sorted them onto a pre-release channel and shipped them.
        assertEquals(ReleaseChannel.Beta,    ReleaseChannel.classify("9.9.9-rc1nightly"))
        assertEquals(ReleaseChannel.Beta,    ReleaseChannel.classify("9.9.9-rc1-nightly5"))
        assertEquals(ReleaseChannel.Release, ReleaseChannel.classify("nightly"))
    }

    @Test
    fun `a dirty or commits-ahead git-describe is a source build (Dev)`() {
        assertEquals(ReleaseChannel.Dev, ReleaseChannel.classify("2.3.4-beta4-17-g5c1a7ee-dirty"))
        assertEquals(ReleaseChannel.Dev, ReleaseChannel.classify("2.3.4-beta4-17-g5c1a7ee"))
        assertEquals(ReleaseChannel.Dev, ReleaseChannel.classify("2.3.4-dirty"))
        assertEquals(ReleaseChannel.Dev, ReleaseChannel.classify("0.0.0-dev"))
        // Building from source off a nightly tag stays a source build: the
        // describe/dirty check must win over the -nightly suffix, otherwise the
        // "never auto-update a source build" guard would leak.
        assertEquals(ReleaseChannel.Dev, ReleaseChannel.classify("2.5.0-nightly1218-5-gabc1234-dirty"))
        assertEquals(ReleaseChannel.Dev, ReleaseChannel.classify("2.5.0-nightly1218-5-gabc1234"))
    }

    @Test
    fun `channels are ordered stable to bleeding-edge`() {
        assertTrue(ReleaseChannel.Release.ordinal < ReleaseChannel.Beta.ordinal)
        assertTrue(ReleaseChannel.Beta.ordinal < ReleaseChannel.Alpha.ordinal)
        assertTrue(ReleaseChannel.Alpha.ordinal < ReleaseChannel.Dev.ordinal)
        assertTrue(ReleaseChannel.Dev.ordinal < ReleaseChannel.Git.ordinal)
    }

    @Test
    fun `only dev and git build from source`() {
        assertTrue(ReleaseChannel.Dev.isSourceBuild)
        assertTrue(ReleaseChannel.Git.isSourceBuild)
        assertFalse(ReleaseChannel.Release.isSourceBuild)
        assertFalse(ReleaseChannel.Beta.isSourceBuild)
        assertFalse(ReleaseChannel.Alpha.isSourceBuild)
    }

    @Test
    fun `settings without updateChannel default to Release`() {
        val loaded = json.decodeFromString<SettingsData>("""{"locale":"de"}""")
        assertEquals(ReleaseChannel.Release, loaded.updateChannel)
    }

    @Test
    fun `a stale prereleaseChannelEnabled field is ignored, channel stays default`() {
        // Old settings.json files carry the retired boolean; lenient parsing
        // drops it rather than failing, and the channel falls to its default.
        val loaded = json.decodeFromString<SettingsData>("""{"prereleaseChannelEnabled":true}""")
        assertEquals(ReleaseChannel.Release, loaded.updateChannel)
    }

    @Test
    fun `updateChannel round-trips through json`() {
        val back = json.decodeFromString<SettingsData>(
            json.encodeToString(SettingsData(updateChannel = ReleaseChannel.Alpha)),
        )
        assertEquals(ReleaseChannel.Alpha, back.updateChannel)
    }
}
