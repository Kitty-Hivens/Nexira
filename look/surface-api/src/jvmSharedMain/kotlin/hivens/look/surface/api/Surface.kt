package hivens.look.surface.api

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import hivens.look.manager.api.LookAxis

/**
 * The surface axis: the planes a widget draws, asked by kind, answered as one [SurfaceAnswers] by the
 * provider the person's choice names, and by [SurfaceAnswers.BASE] where none does. A widget asks
 * through the members here, which read [local].
 *
 * A pack and the person's choice name it by [SurfaceApi.AXIS], and a provider answers it by naming that
 * id as its axis and [SurfaceApi.VERSION] as its contract.
 */
object Surface : LookAxis<SurfaceAnswers>(SurfaceApi.AXIS, SurfaceApi.VERSION, SurfaceAnswers::class, SurfaceAnswers.BASE) {
    override val label: String = SurfaceModule.NAME

    /** See [SurfaceAnswers.plane]. */
    @Composable
    @ReadOnlyComposable
    fun plane(kind: SurfaceKind, holder: Plane, floating: Int): PlaneAnswer = local.current.plane(kind, holder, floating)

    /** See [SurfaceAnswers.plate]. */
    val plate: PlateAnswer
        @Composable @ReadOnlyComposable
        get() = local.current.plate
}
