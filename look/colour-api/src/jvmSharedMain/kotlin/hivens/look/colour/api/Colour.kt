package hivens.look.colour.api

import hivens.look.manager.api.LookAxis

/**
 * The colour axis: what a widget asks of colour, answered as one [ColourAnswers] by the provider the
 * person's choice names, and by [ColourAnswers.BASE] where none does. A widget asks through [Ink],
 * [Paint], [Fill], [Line], [Ground] and [Media], which read [local].
 *
 * A pack and the person's choice name it by [ColourApi.AXIS], and a provider answers it by naming that
 * id as its axis and [ColourApi.VERSION] as its contract.
 */
object Colour : LookAxis<ColourAnswers>(ColourApi.AXIS, ColourApi.VERSION, ColourAnswers::class, ColourAnswers.BASE) {
    override val label: String = ColourModule.NAME
}

/**
 * A feature's own set of things that must look different from each other: the states of a launch, the
 * kinds of a change. Implemented by the feature's enum, next to the feature's code, and asked through
 * [Paint.category], which gives each member its own colour in declaration order.
 */
interface ColourCategory
