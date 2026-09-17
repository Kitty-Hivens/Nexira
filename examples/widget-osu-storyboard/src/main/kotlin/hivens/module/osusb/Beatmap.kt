package hivens.module.osusb

import java.io.File

/**
 * The part of a beatmap a game needs: how hard it is, and when to hit what.
 *
 * Deliberately not the whole format. Slider paths, spinners and hitsound banks
 * are read far enough to place an object in time and space and no further,
 * because what is being tested here is whether the widget kernel can carry a
 * playable, timed, interactive piece -- not whether this file can reimplement a
 * rhythm game faithfully.
 */

enum class NoteKind { Circle, SliderHead, Spinner }

class Note(
    val x: Float,
    val y: Float,
    val time: Int,
    val kind: NoteKind,
    val comboIndex: Int,
)

class Beatmap(
    val title: String,
    val artist: String,
    val version: String,
    val creator: String,
    val audio: String,
    val circleSize: Float,
    val approachRate: Float,
    val overallDifficulty: Float,
    val notes: List<Note>,
) {
    /** How long a note is on screen before its moment, in milliseconds. */
    val preempt: Float =
        if (approachRate < 5f) 1200f + 600f * (5f - approachRate) / 5f
        else if (approachRate > 5f) 1200f - 750f * (approachRate - 5f) / 5f
        else 1200f

    /** Note radius in playfield units. */
    val radius: Float = 54.4f - 4.48f * circleSize

    /** Judgement windows, widest first. */
    val window300: Float = 80f - 6f * overallDifficulty
    val window100: Float = 140f - 8f * overallDifficulty
    val window50: Float = 200f - 10f * overallDifficulty

    val duration: Int = notes.lastOrNull()?.time ?: 0

    companion object {
        /** osu's playfield, which every coordinate in a beatmap is expressed in. */
        const val FIELD_W = 512f
        const val FIELD_H = 384f
    }
}

private fun keyValue(line: String): Pair<String, String>? {
    val i = line.indexOf(':')
    if (i <= 0) return null
    return line.substring(0, i).trim() to line.substring(i + 1).trim()
}

/** Reads one `.osu` difficulty. Returns null when it holds no playable objects. */
fun parseBeatmap(text: String): Beatmap? {
    var section = ""
    val meta = HashMap<String, String>()
    val notes = ArrayList<Note>()
    var combo = 0

    for (raw in text.lineSequence()) {
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("//")) continue
        if (line.startsWith("[") && line.endsWith("]")) {
            section = line.trim('[', ']')
            continue
        }
        when (section) {
            "General", "Metadata", "Difficulty" -> keyValue(line)?.let { (k, v) -> meta[k] = v }
            "HitObjects" -> {
                val p = line.split(',')
                if (p.size < 4) continue
                val x = p[0].trim().toFloatOrNull() ?: continue
                val y = p[1].trim().toFloatOrNull() ?: continue
                val t = p[2].trim().toFloatOrNull()?.toInt() ?: continue
                val type = p[3].trim().toIntOrNull() ?: continue
                // Bit 2 of the type word starts a new combo, which is what the
                // number drawn inside a note counts.
                if (type and 4 != 0 || notes.isEmpty()) combo = 0
                combo++
                val kind = when {
                    type and 8 != 0 -> NoteKind.Spinner
                    type and 2 != 0 -> NoteKind.SliderHead
                    else -> NoteKind.Circle
                }
                if (kind == NoteKind.Spinner) continue
                notes.add(Note(x, y, t, kind, combo))
            }
        }
    }
    if (notes.isEmpty()) return null
    fun num(key: String, fallback: Float) = meta[key]?.toFloatOrNull() ?: fallback
    return Beatmap(
        title = meta["Title"].orEmpty(),
        artist = meta["Artist"].orEmpty(),
        version = meta["Version"].orEmpty(),
        creator = meta["Creator"].orEmpty(),
        audio = meta["AudioFilename"].orEmpty(),
        circleSize = num("CircleSize", 4f),
        approachRate = num("ApproachRate", num("OverallDifficulty", 5f)),
        overallDifficulty = num("OverallDifficulty", 5f),
        notes = notes.sortedBy { it.time },
    )
}

/** Every difficulty in a beatmap folder, easiest name first. */
fun loadBeatmaps(dir: File): List<Beatmap> {
    if (!dir.isDirectory) return emptyList()
    return dir.listFiles { f -> f.isFile && f.name.endsWith(".osu", ignoreCase = true) }
        .orEmpty()
        .sortedBy { it.name }
        .mapNotNull { runCatching { parseBeatmap(it.readText()) }.getOrNull() }
}
