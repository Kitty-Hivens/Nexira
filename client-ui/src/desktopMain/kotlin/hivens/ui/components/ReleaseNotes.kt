package hivens.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import hivens.ui.theme.NxTheme

/**
 * A changelog, rendered at the size of the panel it sits in.
 *
 * The renderer's own defaults map `#` onto `displayLarge` and `###` onto
 * `displaySmall`, which is right for a document and wrong for this: almost every
 * changelog on Modrinth opens with its own version number as a heading, so a
 * three-line note arrived with "1.9.1" set at 36sp above a pane whose own title
 * is 16sp. The heading outshouted the window it was inside.
 *
 * Mapped onto the app's scale instead, and the whole ladder rather than the top
 * of it: an author who writes `##` and an author who writes `####` both mean "a
 * heading", and the gap between two levels is what says which is which.
 */
@Composable
fun ReleaseNotes(markdown: String, modifier: Modifier = Modifier) {
    val type = MaterialTheme.typography
    val colors = NxTheme.colors
    val body = type.bodyMedium
    Markdown(
        content = markdown,
        modifier = modifier,
        colors = markdownColor(
            text = colors.textPrimary,
            codeBackground = colors.surface,
            inlineCodeBackground = colors.surface,
            dividerColor = colors.outline.copy(alpha = 0.25f),
        ),
        typography = markdownTypography(
            h1 = type.titleMedium.copy(fontWeight = FontWeight.Bold),
            h2 = type.titleSmall.copy(fontWeight = FontWeight.Bold),
            h3 = body.copy(fontWeight = FontWeight.Bold),
            h4 = body.copy(fontWeight = FontWeight.SemiBold),
            h5 = type.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            h6 = type.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            text = body,
            paragraph = body,
            ordered = body,
            bullet = body,
            list = body,
            table = body,
            quote = body.copy(fontStyle = FontStyle.Italic, color = colors.textSecondary),
            code = type.bodySmall.copy(fontFamily = FontFamily.Monospace),
            inlineCode = body.copy(fontFamily = FontFamily.Monospace),
            alertTitle = body.copy(fontWeight = FontWeight.Bold),
        ),
    )
}
