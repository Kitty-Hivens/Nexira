package hivens.themes.api

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * What a theme says the interface may be drawn with, in one mode.
 *
 * Nothing here is named after a use. A theme does not know what a card, an accent or
 * an error is. Whoever draws knows that, and asks the scheme for what it needs. That is the
 * whole difference from the record this replaced, whose fields were named after
 * places in the interface and were then quietly overwritten on the way to them.
 *
 * [steps] is the ladder planes are cut from, the page first and outward from it. A
 * theme may give as few as two: past the end, [SchemeColours] continues the ladder in its own
 * direction and character rather than inventing a different one.
 *
 * [inks] is text, strongest first: the main ink, then the quiet one. It is authored
 * against the whole ladder and drawn exactly as given on every plane. Nothing
 * corrects it, so whether it reads on each step is the author's to answer for.
 *
 * [colors] carries the theme's character, variant 1 to N. Order means emphasis and
 * nothing else. Which colour plays which part is decided by the request, not by the
 * theme.
 */
@Immutable
data class Scheme(
    val steps: List<Color>,
    val inks: List<Color>,
    val colors: List<Color>,
) {
    init {
        require(steps.size >= 2) { "a ladder needs at least two steps to have a direction" }
        require(inks.size >= 2) { "a scheme needs a main and a quiet ink" }
        require(colors.isNotEmpty()) { "a scheme needs at least one colour" }
    }

    /** Whether the page is the dark end of this scheme. */
    val isDark: Boolean get() = steps.first().luminance() < 0.5f
}

/**
 * A theme: a dark scheme, and a light one when its author made one.
 *
 * A light scheme is authored, never derived. A theme without one stays in its own
 * mode, and the day/night switch says so rather than inventing the missing half.
 */
@Immutable
data class Theme(
    val id: String,
    val name: String,
    val dark: Scheme,
    val light: Scheme? = null,
) {
    val hasLight: Boolean get() = light != null

    /** The scheme for [dark] mode, or the only one this theme has. */
    fun scheme(dark: Boolean): Scheme = if (dark) this.dark else light ?: this.dark
}
