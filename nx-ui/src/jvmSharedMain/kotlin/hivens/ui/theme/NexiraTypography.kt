package hivens.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import hivens.look.type.api.TextRole
import hivens.look.type.api.Type
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import hivens.nx.ui.generated.resources.Res
import hivens.nx.ui.generated.resources.dejavu_sans
import hivens.nx.ui.generated.resources.jetbrains_mono_bold
import hivens.nx.ui.generated.resources.noto_cjk_jp
import hivens.nx.ui.generated.resources.jetbrains_mono_medium
import hivens.nx.ui.generated.resources.jetbrains_mono_regular
import hivens.nx.ui.generated.resources.jetbrains_mono_semibold
import hivens.nx.ui.generated.resources.roboto_flex_bold
import hivens.nx.ui.generated.resources.roboto_flex_medium
import hivens.nx.ui.generated.resources.roboto_flex_regular
import hivens.nx.ui.generated.resources.roboto_flex_semibold
import org.jetbrains.compose.resources.Font

/**
 * Bundled type. Both families ship inside the app under the SIL Open Font
 * License, so the UI renders identically on every machine instead of inheriting
 * whatever sans the OS happens to default to (which is also how a non-free
 * system font would otherwise leak into the look).
 *
 * - Roboto Flex -- all UI text. Covers Latin, Cyrillic and Greek, so Russian
 *   and German render in the bundled face instead of falling back to a system
 *   font (Google Sans Flex was Latin-only). Its variable source is sliced to
 *   four static weights (400/500/600/700), subset to those scripts, at tooling
 *   time for predictable Skia rendering.
 * - JetBrains Mono -- code / hex / console, read through [LocalMonoFamily] so
 *   call sites swap the platform-generic monospace for the bundled one.
 * - Noto Sans CJK -- see [nexiraCjkFamily], for strings that come off the user's
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
 * cannot borrow coverage from a sibling -- Compose picks one face by weight and
 * style and never by what it contains, so a missing glyph leaves the bundle and
 * lands on whatever the host has, or on nothing. That is why this is a separate
 * family chosen per string by [needsCjkFace] and not another [Font] appended to
 * [nexiraSansFamily].
 *
 * It is the pan-CJK JP face, so it covers Japanese, Chinese in both scripts and
 * Korean with no missing glyph in any of them. Where regional forms diverge the
 * Japanese ones are drawn, which is right for the audience and visibly wrong for
 * Chinese (about one character in five); a second face fixes that when someone
 * needs it, without changing this arrangement. Latin, Greek and Cyrillic are
 * inside it too, because whatever family draws a string has to cover the whole
 * string, and a title mixes scripts more often than not.
 *
 * Regenerate with tools/fonts/regenerate_cjk.py.
 */
@Composable
fun nexiraCjkFamily(): FontFamily {
    val face = Font(Res.font.noto_cjk_jp, FontWeight.Normal)
    return remember(face) { FontFamily(face) }
}

/**
 * The one CJK family for the whole application, provided by [NxTheme].
 *
 * Read this rather than calling [nexiraCjkFamily] from a call site. A family is a
 * cache key in the font resolver, and a fresh instance per call is a fresh entry
 * per call: [familyForText] is asked inside `Text` parameters that recompose with
 * the playback position, five times a second, so a per-call family turned a
 * 7.8 MB face into an unbounded set of registered typefaces. That is heap on Linux
 * and a native font object per entry on macOS.
 */
val LocalCjkFamily = staticCompositionLocalOf<FontFamily?> { null }

/**
 * Which face to draw [text] with, for text the app did not write: a track title
 * off a file's tags, a pack name, a filename, something typed into a widget. The
 * type axis answers it ([Type.forText]).
 *
 * Null means "whatever the style already says", which is the interface face for
 * all but a fraction of strings, so the common case changes nothing. A string that
 * face cannot cover is handed the face the look has for its script, which under nx
 * is the bundled CJK one, rather than leaving the bundle for the host's fonts, which
 * on a machine without a CJK font renders boxes.
 *
 * Asked per string rather than per locale on purpose: a Japanese track title
 * turns up in a Russian interface constantly, and it is the string that decides.
 */
@Composable
fun familyForText(text: String): FontFamily? = Type.forText(text)?.family()

/**
 * DejaVu Sans, bundled solely because it carries the full Braille block (U+2800)
 * at a uniform cell width -- JetBrains Mono has none, so Braille art would tofu or
 * fall back to a random system font. Used only for the decorative Braille console
 * filler; all braille cells share one advance, so picture art stays aligned.
 */
@Composable
fun nexiraBrailleFamily(): FontFamily = FontFamily(Font(Res.font.dejavu_sans))

/**
 * The bundled monospace family, provided by [NxTheme]. Read this instead
 * of `FontFamily.Monospace` so code / hex / console use JetBrains Mono; the
 * default falls back to the platform monospace if a surface renders outside the
 * theme.
 */
val LocalMonoFamily = staticCompositionLocalOf<FontFamily> { FontFamily.Monospace }

/**
 * Material 3's type scale, its sizes, line heights and tracking kept, with each
 * group drawn in the face the type axis answers for its role: display, headline
 * as heading, title, body and label. A face's weight and style replace the slot's
 * where the face gives one.
 *
 * [uiFamily] replaces every role's family, because a locale whose own interface
 * text leaves the interface face has to be drawn by a face that covers it, and
 * the type scale is the one place that decision reaches every role at once.
 */
@Composable
fun lookTypography(uiFamily: FontFamily? = null): Typography {
    val faces = TextRole.entries.associateWith { Type.face(it) }
    val families = TextRole.entries.associateWith { uiFamily ?: faces.getValue(it).family() }
    return remember(faces, families) {
        fun TextStyle.of(role: TextRole): TextStyle {
            val face = faces.getValue(role)
            return copy(
                fontFamily = families.getValue(role),
                fontWeight = face.weight ?: fontWeight,
                fontStyle = face.style ?: fontStyle,
            )
        }
        with(Typography()) {
            copy(
                displayLarge   = displayLarge.of(TextRole.Display),
                displayMedium  = displayMedium.of(TextRole.Display),
                displaySmall   = displaySmall.of(TextRole.Display),
                headlineLarge  = headlineLarge.of(TextRole.Heading),
                headlineMedium = headlineMedium.of(TextRole.Heading),
                headlineSmall  = headlineSmall.of(TextRole.Heading),
                titleLarge     = titleLarge.of(TextRole.Title),
                titleMedium    = titleMedium.of(TextRole.Title),
                titleSmall     = titleSmall.of(TextRole.Title),
                bodyLarge      = bodyLarge.of(TextRole.Body),
                bodyMedium     = bodyMedium.of(TextRole.Body),
                bodySmall      = bodySmall.of(TextRole.Body),
                labelLarge     = labelLarge.of(TextRole.Label),
                labelMedium    = labelMedium.of(TextRole.Label),
                labelSmall     = labelSmall.of(TextRole.Label),
            )
        }
    }
}

@Preview
@Composable
private fun NexiraTypographyDarkPreview() = TypeScale(dark = true)

@Preview
@Composable
private fun NexiraTypographyLightPreview() = TypeScale(dark = false)

@Composable
private fun TypeScale(dark: Boolean) {
    Previewed(dark = dark) {
        val type = MaterialTheme.typography
        Column(
            modifier            = Modifier.width(560.dp).background(NxColor.page).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            for ((role, style) in listOf(
                "Headline small" to type.headlineSmall,
                "Title large" to type.titleLarge,
                "Title medium" to type.titleMedium,
                "Title small" to type.titleSmall,
                "Body large" to type.bodyLarge,
                "Body medium" to type.bodyMedium,
                "Body small" to type.bodySmall,
                "Label large" to type.labelLarge,
                "Label medium" to type.labelMedium,
                "Label small" to type.labelSmall,
            )) {
                Text("$role, the quick brown fox", style = style, color = NxInk.main)
            }
            Text("Mono 0123456789 #3B6EA8", style = type.bodyMedium.copy(fontFamily = LocalMonoFamily.current), color = NxInk.main)
        }
    }
}
