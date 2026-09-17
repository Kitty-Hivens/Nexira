package hivens.module.osusb

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Parses a real beatmap folder when one is pointed at, and says what it found.
 *
 * Skipped when the property is absent, so the suite stays green on a machine
 * that has no beatmaps. Run it with
 * `-Dosusb.folder=/path/to/Songs/<beatmap>` to check the parser against
 * something nobody wrote for it.
 */
class RealStoryboardTest {

    @Test
    fun `parses a real beatmap folder when one is given`() {
        val folder = System.getProperty("osusb.folder") ?: return
        val dir = File(folder)
        assertTrue(dir.isDirectory, "not a directory: $folder")

        val sb = loadStoryboard(dir)
        assertTrue(sb != null, "no storyboard found in $folder")

        val tweens = sb.sprites.sumOf { it.tweens.size }
        val byLayer = SbLayer.entries.associateWith { l -> sb.sprites.count { it.layer == l } }
        val missing = sb.sprites.map { it.path }.distinct().filter { p ->
            val direct = File(dir, p)
            !direct.isFile && dir.walkTopDown().none { it.isFile && it.name.equals(p.substringAfterLast('/'), true) }
        }

        println("sprites      : ${sb.sprites.size}")
        println("tweens       : $tweens")
        println("duration     : ${sb.duration} ms (${sb.duration / 1000}s)")
        println("by layer     : " + byLayer.filterValues { it > 0 })
        println("images used  : ${sb.sprites.map { it.path }.distinct().size}")
        println("images absent: ${missing.size} ${missing.take(5)}")

        // Evaluate every sprite across the whole timeline. This is the real
        // assertion: not a count, but that nothing throws and that something is
        // actually visible at a sample of instants.
        val state = SpriteState()
        var visibleSamples = 0
        var samples = 0
        var t = 0
        while (t <= sb.duration) {
            var anyVisible = false
            for (s in sb.sprites) if (evaluate(s, t, state)) anyVisible = true
            samples++
            if (anyVisible) visibleSamples++
            t += 250
        }
        println("samples      : $samples, with something visible: $visibleSamples")
        assertTrue(sb.sprites.isNotEmpty(), "no sprites parsed")
        assertTrue(tweens > 0, "no tweens parsed")
        assertTrue(visibleSamples > samples / 4, "the storyboard is blank for most of its length")
    }
}
