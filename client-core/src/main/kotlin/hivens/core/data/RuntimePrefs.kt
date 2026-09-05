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

    /**
     * Heap the user pinned for this instance, in MB. Zero means they pinned
     * nothing, which is the state a record is created in and the state it stays
     * in unless somebody picks a number: the launch path sizes an unpinned
     * instance from the machine and then from what the adaptive sizer measured,
     * and never reads this. A record used to be born carrying a heap it had no
     * reason to name, and two of them named different ones.
     */
    val memoryMb: Int
    /** Use [memoryMb] instead of letting the global adaptive sizer decide. */
    val fixedMemory: Boolean
    val jvmArgs: String?
    val windowWidth: Int
    val windowHeight: Int
    val fullScreen: Boolean

    companion object {
        /** [memoryMb] of a record nobody has pinned a heap on. */
        const val NO_PINNED_MEMORY = 0

        const val WINDOW_WIDTH = 925
        const val WINDOW_HEIGHT = 530
    }
}
