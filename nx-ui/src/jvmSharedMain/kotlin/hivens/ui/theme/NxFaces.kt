package hivens.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.Font as FontFace
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import hivens.nx.ui.generated.resources.Res
import hivens.nx.ui.generated.resources.jetbrains_mono_bold
import hivens.nx.ui.generated.resources.jetbrains_mono_medium
import hivens.nx.ui.generated.resources.jetbrains_mono_regular
import hivens.nx.ui.generated.resources.jetbrains_mono_semibold
import hivens.nx.ui.generated.resources.noto_cjk_jp
import hivens.nx.ui.generated.resources.roboto_flex_bold
import hivens.nx.ui.generated.resources.roboto_flex_medium
import hivens.nx.ui.generated.resources.roboto_flex_regular
import hivens.nx.ui.generated.resources.roboto_flex_semibold
import hivens.ui.text.needsCjkFace
import java.util.concurrent.ConcurrentHashMap
import org.jetbrains.compose.resources.Font

/**
 * Bundled type. Both families ship inside the app under the SIL Open Font
 * License, so the UI renders identically on every machine instead of inheriting
 * whatever sans the OS happens to default to (which is also how a non-free
 * system font would otherwise leak into the look).
 *
 * - Roboto Flex: all UI text. Covers Latin, Cyrillic and Greek, so Russian
 *   and German render in the bundled face instead of falling back to a system
 *   font (Google Sans Flex was Latin-only). Its variable source is sliced to
 *   four static weights (400/500/600/700), subset to those scripts, at tooling
 *   time for predictable Skia rendering.
 * - JetBrains Mono: code / hex / console, read through `LocalMonoFamily` so
 *   call sites swap the platform-generic monospace for the bundled one.
 * - Noto Sans CJK: see [nexiraCjkFamily], for strings that come off the user's
 *   disk rather than from the app.
 */
@Composable
fun nexiraSansFamily(): FontFamily = FontFamily(
    Font(Res.font.roboto_flex_regular,  FontWeight.Normal),
    Font(Res.font.roboto_flex_medium,   FontWeight.Medium),
    Font(Res.font.roboto_flex_semibold, FontWeight.SemiBold),
    Font(Res.font.roboto_flex_bold,     FontWeight.Bold),
)

@Composable
fun nexiraMonoFamily(): FontFamily = FontFamily(
    Font(Res.font.jetbrains_mono_regular,  FontWeight.Normal),
    Font(Res.font.jetbrains_mono_medium,   FontWeight.Medium),
    Font(Res.font.jetbrains_mono_semibold, FontWeight.SemiBold),
    Font(Res.font.jetbrains_mono_bold,     FontWeight.Bold),
)

/**
 * The bundled CJK face, for text the app did not write: track titles, pack names,
 * file names. Roboto Flex is subset to Latin, Cyrillic and Greek, and a family
 * cannot borrow coverage from a sibling: Compose picks one face by weight and
 * style and never by what it contains, so a missing glyph leaves the bundle and
 * lands on whatever the host has, or on nothing. That is why this is a separate
 * family chosen per string by [needsCjkFace] and not another [Font] appended to
 * [nexiraSansFamily].
 *
 * It is the pan-CJK JP face, so it covers Japanese, Chinese in both scripts and
 * Korean with no missing glyph in any of them. Where regional forms diverge the
 * Japanese ones are drawn, which is right for the audience and visibly wrong for
 * Chinese (about one character in five). A second face fixes that when someone
 * needs it, without changing this arrangement. Latin, Greek and Cyrillic are
 * inside it too, because whatever family draws a string has to cover the whole
 * string, and a title mixes scripts more often than not.
 *
 * Regenerate with tools/fonts/regenerate.py.
 */
@Composable
fun nexiraCjkFamily(): FontFamily {
    val face = Font(Res.font.noto_cjk_jp, FontWeight.Normal)
    return cjkFamilies.computeIfAbsent(face) { FontFamily(it) }
}

/**
 * The family of each face [nexiraCjkFamily] was handed, one once the font is read. A family is a cache
 * key in the font resolver, and `familyForText` is asked inside `Text` parameters that recompose with the
 * playback position, five times a second: a family per call turned a 7.8 MB face into an unbounded set of
 * registered typefaces. That is heap on Linux and a native font object per entry on macOS.
 */
private val cjkFamilies = ConcurrentHashMap<FontFace, FontFamily>()
