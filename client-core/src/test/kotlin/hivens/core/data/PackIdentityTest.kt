package hivens.core.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PackIdentityTest {

    private fun instance(
        id: String = "8f14e45f-ea0d-4f8a-9c2b-1f2d3e4a5b6c",
        packRef: PackReference = PackReference(PackOrigin.Mirror, "industrial", "2026.01.01"),
        instanceDirName: String = "industrial-8f14e45f",
        forkedFrom: PackReference? = null,
        pinnedPackVersion: String? = "2026.01.01",
    ) = PackInstance(
        id = id,
        packRef = packRef,
        displayName = "Industrial",
        instanceDirName = instanceDirName,
        createdAtEpoch = 0L,
        forkedFrom = forkedFrom,
        pinnedPackVersion = pinnedPackVersion,
    )

    @Test
    fun `a reference with an id and a pin is valid`() {
        assertTrue(PackIdentity.isValid(PackReference(PackOrigin.Mirror, "industrial", "2026.01.01")))
    }

    @Test
    fun `a floating reference is valid`() {
        assertTrue(PackIdentity.isValid(PackReference(PackOrigin.Local, "my-pack", version = null)))
    }

    @Test
    fun `a reference without an id names no pack`() {
        assertFalse(PackIdentity.isValid(PackReference(PackOrigin.Mirror, "")))
        assertFalse(PackIdentity.isValid(PackReference(PackOrigin.Mirror, "   ")))
    }

    @Test
    fun `a blank version is neither a pin nor floating`() {
        assertFalse(PackIdentity.isValid(PackReference(PackOrigin.Mirror, "industrial", "")))
    }

    @Test
    fun `require returns a well-formed instance unchanged`() {
        val subject = instance()
        assertSame(subject, PackIdentity.require(subject))
    }

    @Test
    fun `require names the field it rejected`() {
        val blankId = assertFailsWith<IllegalArgumentException> { PackIdentity.require(instance(id = "")) }
        assertTrue(blankId.message!!.contains("instance id"), blankId.message)

        val blankDir = assertFailsWith<IllegalArgumentException> {
            PackIdentity.require(instance(instanceDirName = ""))
        }
        assertTrue(blankDir.message!!.contains("instanceDirName"), blankDir.message)

        val danglingRef = assertFailsWith<IllegalArgumentException> {
            PackIdentity.require(instance(packRef = PackReference(PackOrigin.Mirror, "")))
        }
        assertTrue(danglingRef.message!!.contains("packRef"), danglingRef.message)

        val danglingFork = assertFailsWith<IllegalArgumentException> {
            PackIdentity.require(instance(forkedFrom = PackReference(PackOrigin.Local, " ")))
        }
        assertTrue(danglingFork.message!!.contains("forkedFrom"), danglingFork.message)

        val blankPin = assertFailsWith<IllegalArgumentException> {
            PackIdentity.require(instance(pinnedPackVersion = ""))
        }
        assertTrue(blankPin.message!!.contains("pinnedPackVersion"), blankPin.message)
    }

    @Test
    fun `a fork of a well-formed reference is accepted`() {
        val forked = instance(forkedFrom = PackReference(PackOrigin.Mirror, "industrial", "2025.12.01"))
        assertSame(forked, PackIdentity.require(forked))
    }

    @Test
    fun `normalize turns every blank version into an absent one`() {
        val repaired = PackIdentity.normalize(
            instance(
                packRef = PackReference(PackOrigin.Mirror, "industrial", ""),
                forkedFrom = PackReference(PackOrigin.Mirror, "industrial", ""),
                pinnedPackVersion = "",
            ),
        )
        assertNull(repaired.packRef.version)
        assertNull(repaired.forkedFrom!!.version)
        assertNull(repaired.pinnedPackVersion)
        assertTrue(PackIdentity.isValid(repaired))
    }

    @Test
    fun `normalize keeps a real pin and every other field`() {
        val subject = instance(forkedFrom = PackReference(PackOrigin.Local, "base", "1.0"))
        val normalized = PackIdentity.normalize(subject)
        assertEquals(subject, normalized)
    }

    @Test
    fun `normalize cannot repair a missing id`() {
        assertFalse(PackIdentity.isValid(PackIdentity.normalize(instance(id = ""))))
    }
}
