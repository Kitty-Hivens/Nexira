package hivens.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance

/**
 * What a theme says the interface may be drawn with, in one mode.
 *
 * Nothing here is named after a use. A theme does not know what a card, an accent or
 * an error is. nx-ui knows that, and asks the scheme for what it needs. That is the
 * whole difference from the record this replaced, whose fields were named after
 * places in the interface and were then quietly overwritten on the way to them.
 *
 * [steps] is the ladder planes are cut from, the page first and outward from it. A
 * theme may give as few as two: past the end, nx-ui continues the ladder in its own
 * direction and character rather than inventing a different one.
 *
 * [inks] is text, strongest first: the main ink, then the quiet one. It is authored
 * against the whole ladder and has to stay readable on every step, which the theme
 * tests hold it to.
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

/**
 * The few meanings colour carries by convention, the ones a reader knows without a
 * label. Closed on purpose: a new status is a design decision in this library, not a
 * line a feature adds. Everything else that needs telling apart is a [ColorCategory].
 */
enum class Status { Error, Warning, Success, Info }

/**
 * A feature's own set of things that must look different from each other: the states
 * of a launch, the kinds of a change, the channels of a release.
 *
 * Implemented by the feature's enum, next to the feature's code. Members get distinct
 * colours of the active theme in declaration order, so one member always gets one
 * colour, and adding a member hands it the next one.
 */
interface ColorCategory
