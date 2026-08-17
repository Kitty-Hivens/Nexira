package hivens.core.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Contract of [foldLegacyExperimentalGate]: a file written with the retired master
 * switched off carries an intent -- four features were off -- that the knobs
 * themselves never recorded, because two of them default to on. The fold writes
 * that intent onto the knobs and clears the flag, so the next start honours what
 * the user chose instead of turning their heap sizing and pack updates back on.
 */
class ExperimentalGateMigrationTest {

    @Test
    fun a_file_that_never_touched_the_master_is_left_alone() {
        val fresh = SettingsData()
        assertEquals(fresh, foldLegacyExperimentalGate(fresh))
    }

    @Test
    fun master_off_folds_onto_the_knobs_it_suppressed() {
        val legacy = SettingsData(
            experimentalFeaturesEnabled = false,
            mandatoryUpdatesEnabled     = true,
            autoSyncAllPacks            = true,
            autoUpdatePacks             = true,
            adaptiveMemoryEnabled       = true,
        )
        val folded = foldLegacyExperimentalGate(legacy)

        assertFalse(folded.mandatoryUpdatesEnabled)
        assertFalse(folded.autoSyncAllPacks)
        assertFalse(folded.autoUpdatePacks)
        assertFalse(folded.adaptiveMemoryEnabled)
    }

    @Test
    fun the_fold_clears_itself_so_it_cannot_fire_twice() {
        val legacy = SettingsData(experimentalFeaturesEnabled = false)
        val folded = foldLegacyExperimentalGate(legacy)
        assertTrue(folded.experimentalFeaturesEnabled)

        // The user re-enables one of the knobs; folding again must not undo that.
        val reEnabled = folded.copy(adaptiveMemoryEnabled = true)
        assertTrue(foldLegacyExperimentalGate(reEnabled).adaptiveMemoryEnabled)
    }

    @Test
    fun knobs_the_master_only_greyed_out_are_left_alone() {
        // The gate greyed these two rows out but never reached their readers:
        // ServerSettingsState reads jvmBuilderEnabled directly, and
        // SettingsRestoreHook applies the mimic override on every start. They were
        // live with the master off, so switching it off expressed no intent here.
        val legacy = SettingsData(
            experimentalFeaturesEnabled = false,
            jvmBuilderEnabled           = true,
            mimicVersionOverride        = "3.6.5",
        )
        val folded = foldLegacyExperimentalGate(legacy)

        assertTrue(folded.jvmBuilderEnabled)
        assertEquals("3.6.5", folded.mimicVersionOverride)
    }
}
