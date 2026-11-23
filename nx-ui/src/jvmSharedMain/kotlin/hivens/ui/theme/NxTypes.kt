package hivens.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import hivens.look.type.api.TypeAnswers
import hivens.look.type.api.TypeFace
import hivens.ui.text.needsCjkFace

/**
 * nx's faces as the type axis's answers: what the nx type provider answers, and what [NxThemed] draws
 * previews and tests in. Made once, so a role is the same value wherever it is read.
 */
fun nxTypeAnswers(): TypeAnswers = NxType.ANSWERS

/**
 * The nx faces, all bundled. Every role is drawn in Roboto Flex at the weight the component asks for,
 * code in JetBrains Mono, and a string Roboto Flex cannot cover, Chinese, Japanese or Korean, in Noto
 * Sans CJK.
 */
private object NxType {
    val ANSWERS: TypeAnswers = TypeAnswers.build {
        face = { Sans }
        mono = Mono
        forText = { text -> if (needsCjkFace(text)) Cjk else null }
    }
}

/** Roboto Flex, see [nexiraSansFamily]. */
private object Sans : TypeFace() {
    @Composable
    override fun family(): FontFamily = nexiraSansFamily()
    override fun toString(): String = "Roboto Flex"
}

/** JetBrains Mono, see [nexiraMonoFamily]. */
private object Mono : TypeFace() {
    @Composable
    override fun family(): FontFamily = nexiraMonoFamily()
    override fun toString(): String = "JetBrains Mono"
}

/**
 * Noto Sans CJK, see [nexiraCjkFamily]: the one instance [NxTheme] provides where there is one, since
 * a family is a cache key in the font resolver and this face is asked inside `Text` parameters that
 * recompose with a playback position.
 */
private object Cjk : TypeFace() {
    @Composable
    override fun family(): FontFamily = LocalCjkFamily.current ?: nexiraCjkFamily()
    override fun toString(): String = "Noto Sans CJK"
}
