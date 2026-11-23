package hivens.look.timing.api

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import hivens.look.manager.api.LookAxis

/**
 * The timing axis: how long a transient piece of the interface waits before it appears, asked by what
 * it is, answered as one [TimingAnswers] by the provider the person's choice names, and by
 * [TimingAnswers.BASE] where none does. A widget asks through the members here, which read [local].
 *
 * A pack and the person's choice name it by [TimingApi.AXIS], and a provider answers it by naming that
 * id as its axis and [TimingApi.VERSION] as its contract.
 */
object Timing : LookAxis<TimingAnswers>(TimingApi.AXIS, TimingApi.VERSION, TimingAnswers::class, TimingAnswers.BASE) {
    override val label: String = TimingModule.NAME

    /** See [TimingAnswers.tooltipDelay]. */
    @Composable
    @ReadOnlyComposable
    fun tooltipDelay(kind: TooltipKind): Int = local.current.tooltipDelay(kind)
}
