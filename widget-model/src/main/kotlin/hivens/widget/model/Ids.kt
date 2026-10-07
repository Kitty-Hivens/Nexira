package hivens.widget.model

import kotlinx.serialization.Serializable

@JvmInline
@Serializable
value class SurfaceId(val value: String)

@JvmInline
@Serializable
value class SlotId(val value: String)

@JvmInline
@Serializable
value class WidgetKind(val value: String)

/**
 * Names one of a surface's alternative slot sets.
 *
 * A surface does not have slots, it has families of them, and only one family is
 * live at a time. Which one is runtime state that code sets: a right rail
 * showing [GENERAL] carries messages and the media layer, and the same rail
 * switched to a project view carries that project's data instead. Both sets stay
 * in the file, because the reader arranged both and neither is the other's
 * previous state.
 */
@JvmInline
@Serializable
value class FamilyId(val value: String) {
    companion object {
        /** The family every surface has, and the one a surface falls back to. */
        val GENERAL: FamilyId = FamilyId("general")
    }
}
