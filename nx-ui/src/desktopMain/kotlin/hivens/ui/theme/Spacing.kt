package hivens.ui.theme

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The one spacing scale: gaps, paddings and inter-element arrangement pull from
 * here instead of picking a fresh dp per call site, so sibling surfaces stay in
 * step and a drifting value has a single place to be corrected.
 *
 * The ladder is a rule, not a list. Steps of 2 up to sixteen, steps of 4 up to
 * twenty four, then 32. Every rung earns its place: the previous seven-rung
 * version was derived from an ideal rather than from this codebase, and left out
 * four of the most widely used values, so 6, 10, 14 and 20 dp had nowhere to go
 * and the scale ended up with no call sites at all. A scale that cannot express
 * what the interface already does is decoration.
 *
 * [s14] is the clearest case of that: it is the inner padding of every card in
 * the app, the horizontal inset of a row or a menu item, and the gap in every
 * grid, across thirty one files that arrived at it independently.
 *
 * Rungs are named for what they measure. Reading `s6` at a call site says the
 * same thing `6.dp` did, and adds the one fact the literal could not: that the
 * value is on the scale. A value that is not here is a value to move to the
 * nearest rung, not a rung to add.
 *
 * These are base dp. The global density knob (customization densityScale,
 * applied as a [androidx.compose.ui.unit.Density] override at the shell root)
 * multiplies them at layout time, so the scale composes with user density
 * without any per-token arithmetic.
 *
 * Spacing only. A component's own dimensions, a corner radius, a stroke or an
 * icon size are not gaps between things, and they answer to [Form] or to the
 * component itself.
 */
object Spacing {
    /** Hairline gap: icon to label, chip inner padding. */
    val s2: Dp = 2.dp
    /** Tight gap: dense rows, badge padding. */
    val s4: Dp = 4.dp
    val s6: Dp = 6.dp
    /** The default small gap, and the most used value in the tree. */
    val s8: Dp = 8.dp
    val s10: Dp = 10.dp
    /** Component inner padding: card content, list rows. */
    val s12: Dp = 12.dp
    /** Card inset, row inset, grid gap. The app's own unit of rhythm. */
    val s14: Dp = 14.dp
    /** Section padding: panel edges, dialog content. */
    val s16: Dp = 16.dp
    val s20: Dp = 20.dp
    /** Block separation, between grouped sections. */
    val s24: Dp = 24.dp
    /** Page-level breathing room. */
    val s32: Dp = 32.dp

    /** Every rung, ascending. The ladder itself, for a check that wants to walk it. */
    val rungs: List<Dp> = listOf(s2, s4, s6, s8, s10, s12, s14, s16, s20, s24, s32)
}
