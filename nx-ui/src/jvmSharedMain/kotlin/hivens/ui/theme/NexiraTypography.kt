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
import hivens.look.type.api.TextRole
import hivens.look.type.api.Type
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp

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
