package hivens.widget.api

import androidx.compose.ui.graphics.Color

/**
 * One mote of a particle field at one moment: where it is and how it looks, in dp.
 * [colour] picks from the field's palette by number, modulo its size.
 */
data class ParticleMote(val x: Float, val y: Float, val radius: Float, val alpha: Float, val colour: Int)

/** What the field may colour its motes from: the theme's inks and accent colours, best first. */
data class ParticlePalette(val ink: Color, val quiet: Color, val colors: List<Color>)

/**
 * A field of particles a module brings. It becomes a widget, placed from the editor's palette under
 * the module's name like any other, with a density the person sets, and it is drawn in the theme's
 * colours and held still when the person asked for less movement.
 *
 * It is a rule and not a simulation: where mote number `i` is at time `t`, and nothing else. That is
 * what keeps a frame to one pass over the motes with nothing allocated per mote that the rule does
 * not allocate itself, and what makes the same field the same on every run. Travel is expected to
 * wrap around the area, which keeps the count constant without spawning anything.
 */
interface ParticleContribution : ModuleContribution {
    /** The colours [moteAt]'s `colour` numbers pick from. Called when the theme changes, not per frame. */
    fun palette(of: ParticlePalette): List<Color>

    /** Mote [i] at [t] seconds in a [w] by [h] dp area. Called for every mote on every frame: keep it cheap and pure. */
    fun moteAt(i: Int, t: Float, w: Float, h: Float): ParticleMote
}
