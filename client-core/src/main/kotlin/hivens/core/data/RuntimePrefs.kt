package hivens.core.data

/**
 * What an instance says about the JVM it wants: which one, how much heap, which
 * flags, and what the game window opens as.
 *
 * Two records answer this and neither can be dropped for the other.
 * [InstanceProfile] belongs to a SmartyCraft server and also carries the
 * per-mod selection that model needs; [InstanceRuntime] belongs to a pack
 * instance, which handles optional content as a list of toggles instead. Both
 * are persisted under their own wire shapes, so composing one type into the
 * other would push every field a level deeper in files already written -- an
 * instance would come back from disk having forgotten its heap.
 *
 * They share this instead. A consumer that only needs to know what to launch
 * with takes [RuntimePrefs] and stops caring which of the two it was handed,
 * and the defaults below are stated once rather than drifting apart in two
 * constructors and a composable.
 */
interface RuntimePrefs {
    /** Absolute path to a `java` executable, or null to let the launcher resolve one. */
    val javaPath: String?
    val memoryMb: Int
    /** Pin [memoryMb] instead of letting the global adaptive sizer decide. */
    val fixedMemory: Boolean
    val jvmArgs: String?
    val windowWidth: Int
    val windowHeight: Int
    val fullScreen: Boolean

    companion object {
        /**
         * Heap a per-server profile starts at. 6 GB matches modded-MC reality --
         * SmartyCraft packs run 50-70 mods and want 4-6 GB to be smooth.
         *
         * Neither this nor [PACK_MEMORY_MB] is what a launch uses unless the
         * instance is pinned: an unpinned one goes to the machine-aware
         * baseline. What they decide is the number the RAM selector offers when
         * the user leaves Auto.
         */
        const val SERVER_MEMORY_MB = 6144

        /**
         * Heap a pack instance starts at. Lower than [SERVER_MEMORY_MB], and
         * the two have never been reconciled -- they are kept apart here so a
         * change to either is a decision rather than a place someone forgot.
         */
        const val PACK_MEMORY_MB = 4096

        const val WINDOW_WIDTH = 925
        const val WINDOW_HEIGHT = 530
    }
}
