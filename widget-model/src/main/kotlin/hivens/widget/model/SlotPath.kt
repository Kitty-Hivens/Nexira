package hivens.widget.model

// Canonical address for a slot in the layout tree.
//
// A path starts at a top-level (surface, rootSlot) pair and may descend
// through one or more nested segments, each of which identifies the
// WidgetInstance whose `children[slot]` we are entering. An empty
// `nested` list means the path points at a top-level slot -- behavior
// is identical to the legacy SlotAddress.
//
// The first hop is structurally different from nested hops (the graph
// keys top-level slots by surface, but nested slots by a parent widget
// instance id), so the type carries `rootSlot` separately rather than
// modelling it as the head of a uniform segment list with a nullable
// parent.
data class SlotPath(
    val surface: SurfaceId,
    val rootSlot: SlotId,
    val nested: List<NestedSegment> = emptyList(),
) {
    val leafSlot: SlotId
        get() = nested.lastOrNull()?.slot ?: rootSlot

    val leafAddress: SlotAddress
        get() = SlotAddress(surface, leafSlot)

    val parentPath: SlotPath?
        get() = if (nested.isEmpty()) null else copy(nested = nested.dropLast(1))

    fun child(parentInstanceId: String, slot: SlotId): SlotPath =
        copy(nested = nested + NestedSegment(parentInstanceId, slot))

    override fun toString(): String = buildString {
        append(surface.value).append(':').append(rootSlot.value)
        for (segment in nested) {
            append(" > ").append(segment.parentInstanceId).append(':').append(segment.slot.value)
        }
    }
}

// One hop into a container widget. `parentInstanceId` identifies the
// WidgetInstance whose `children[slot]` we are entering. The slot
// itself is the destination.
data class NestedSegment(
    val parentInstanceId: String,
    val slot: SlotId,
)

fun SlotAddress.toPath(): SlotPath = SlotPath(surface, slot)
