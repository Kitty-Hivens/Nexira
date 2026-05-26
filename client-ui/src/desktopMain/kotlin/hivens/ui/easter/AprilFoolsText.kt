package hivens.ui.easter

import kotlin.random.Random

/**
 * Provides chaos text transformations for the About screen.
 *
 * On April Fools, some strings are randomly replaced with:
 *  - Lorem ipsum fragments
 *  - Zalgo-style character corruption
 *  - Fake version numbers
 *  - Completely unrelated technical jargon
 *
 * [maybeGibberish] is the main entry point -- call it around any
 * string that should occasionally corrupt itself.
 */
object AprilFoolsText {

    private val loremFragments = listOf(
        "Lorem ipsum dolor sit amet",
        "consectetur adipiscing elit",
        "sed do eiusmod tempor incididunt",
        "NullPointerException at line 42",
        "Segmentation fault (core dumped)",
        "Error 418: I'm a teapot",
        "Congratulations! You have died.",
        "Happy birthday",
        $$"javax.swing.SwingUtilities$2.run(Unknown Source)",
        "at sun.reflect.NativeMethodAccessorImpl.invoke0",
        "FATAL: connection to server lost",
        "Warning: undefined behavior detected",
        "TODO: Fix stability",
        "// this code was written at 3am",
        "Stack overflow in stack overflow handler",
        "OutOfMemoryError: Java heap space",
        "Caused by: who knows at this point",
    )

    private val zalgoChars = listOf(
        '\u0300', '\u0301', '\u0302', '\u0303', '\u0308',
        '\u0330', '\u0331', '\u0332', '\u0333', '\u0334',
    )

    private val fakeVersions = listOf(
        "v0.0.0-alpha-pre-beta-rc1",
        "v∞.∞.∞",
        "v-1.0.0",
        "vNaN.undefined.null",
        "v2026.04.01-april-fools",
        "v9999.0.0-SNAPSHOT",
    )

    private val jargon = listOf(
        "Kotlin Multiplatform Gradle DSL Compose M3 Ktor Koin Skiko JVM",
        "Reticulating splines...",
        "Initializing quantum flux capacitor",
        "Defragmenting memory blocks 0x00 to 0xFF",
        "Calculating the meaning of life... result: 42",
        "Compiling Rust at 0.001 MB/s",
        "Waiting for GC... (estimated: never)",
    )

    /**
     * Returns a corrupted version of [text] with [probability] chance.
     * When April Fools is inactive, always returns [text] unchanged.
     *
     * @param text         Original string to potentially corrupt.
     * @param probability  0.0..1.0 chance of corruption per call (default 0.25).
     * @param mode         Which corruption style to use (null = random).
     */
    fun maybeGibberish(
        text: String,
        probability: Float = 0.25f,
        mode: GibberishMode? = null,
    ): String {
        if (!AprilFools.isActive()) return text
        if (Random.nextFloat() > probability) return text

        val chosenMode = mode ?: GibberishMode.entries.random()
        return when (chosenMode) {
            GibberishMode.LOREM      -> loremFragments.random()
            GibberishMode.ZALGO      -> zalgoify(text)
            GibberishMode.FAKE_VER   -> fakeVersions.random()
            GibberishMode.JARGON     -> jargon.random()
            GibberishMode.REVERSED   -> text.reversed()
            GibberishMode.SCRAMBLED  -> text.toList().shuffled().joinToString("")
        }
    }

    private fun zalgoify(text: String): String = buildString {
        text.forEach { c ->
            append(c)
            // Add 1–3 random combining characters after each letter
            repeat(Random.nextInt(1, 4)) {
                append(zalgoChars.random())
            }
        }
    }
}
