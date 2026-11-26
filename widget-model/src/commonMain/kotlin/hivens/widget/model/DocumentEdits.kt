package hivens.widget.model

/** Where in a composition a path starts. */
sealed interface NodeRoot {
    /** The root of one layout of a config. */
    data class Layout(val config: ConfigId, val layout: LayoutId) : NodeRoot

    /** The composition's residents. */
    data object Residents : NodeRoot

    /**
     * A slot of a surface of the [LayoutGraph], as [SlotPath] names it. Read from the graph and never from a document:
     * a document holds no surfaces, so [contentAt] answers null for it and [edit] leaves the document as it is.
     */
    data class Surface(val surface: SurfaceId, val slot: SlotId, val family: FamilyId = FamilyId.GENERAL) : NodeRoot
}

/**
 * The address of one slot's content in a [Document]: where it starts and the containers descended through.
 *
 * A path that descends through one of the composition's singles addresses that widget's one record, whichever
 * config it was reached by, so an edit made through one config is seen in all of them. A slot the config holds as its
 * own is the exception: the path then addresses what that config holds there.
 */
data class NodePath(
    val composition: CompositionName,
    val root: NodeRoot,
    val nested: List<NestedSegment> = emptyList(),
) {
    val parentPath: NodePath? get() = if (nested.isEmpty()) null else copy(nested = nested.dropLast(1))

    fun child(parentInstanceId: String, slot: SlotId): NodePath = copy(nested = nested + NestedSegment(parentInstanceId, slot))
}

/** The content at [path], every single in it as it stands, or null where the path leads nowhere. */
fun Document.contentAt(path: NodePath): SlotContent? {
    val composition = compositions[path.composition] ?: return null
    return composition.rawContentAt(path)?.let { composition.stand(it) }
}

/** The content at [path] as the file holds it: a single there is only where it stands and the slots held as own. */
private fun Composition.rawContentAt(path: NodePath): SlotContent? {
    var content = rootContent(path.root) ?: return null
    for (segment in path.nested) {
        val container = content.widgets.firstOrNull { it.instanceId == segment.parentInstanceId } ?: return null
        content = standing(container).children[segment.slot] ?: return null
    }
    return content
}

/**
 * The document with the content at [path] replaced by what [edit] makes of it.
 *
 * [edit] is given the content with every single in it as it stands, and what it changes of one goes to the record
 * kept once for the composition, but for the slots this config holds as its own, which stay the config's. Where it
 * stands stays the config's, so a single is moved in one config and changed in all. Returns this very document where
 * the path leads nowhere or [edit] changes nothing, since an edit races with a document being replaced and one on what
 * is gone must not fail.
 */
fun Document.edit(path: NodePath, edit: (SlotContent) -> SlotContent): Document = change(path) { content, singles ->
    val standing = content.standingOn(singles)
    val edited = edit(standing)
    if (edited === standing) content else edited.settleInto(singles, content)
}

/**
 * The document with the content at [path] replaced by what [change] makes of it as the file holds it, given the
 * singles to settle anything it changes of them into.
 */
private fun Document.change(
    path: NodePath,
    change: (SlotContent, MutableMap<WidgetKind, WidgetInstance>) -> SlotContent,
): Document {
    val composition = compositions[path.composition] ?: return this
    val root = composition.rootContent(path.root) ?: return this
    val singles = composition.singles.toMutableMap()
    val next = changeContent(root, path.nested, singles, change)
    if (next === root && singles == composition.singles) return this
    return copy(compositions = compositions + (path.composition to composition.copy(singles = singles).withRoot(path.root, next)))
}

private fun changeContent(
    content: SlotContent,
    nested: List<NestedSegment>,
    singles: MutableMap<WidgetKind, WidgetInstance>,
    change: (SlotContent, MutableMap<WidgetKind, WidgetInstance>) -> SlotContent,
): SlotContent {
    if (nested.isEmpty()) return change(content, singles)
    val segment = nested.first()
    val container = content.widgets.firstOrNull { it.instanceId == segment.parentInstanceId } ?: return content
    // A slot of a single goes to its record, unless this config holds the slot as its own.
    val toRecord = container.kind in singles && segment.slot !in container.children
    val holder = if (toRecord) singles.getValue(container.kind) else container
    val child = holder.children[segment.slot] ?: return content
    val nextChild = changeContent(child, nested.drop(1), singles, change)
    if (nextChild === child) return content
    if (toRecord) {
        // Read again: a change further down may have changed this very record, a single inside a single of its own kind say.
        val latest = singles.getValue(container.kind)
        singles[container.kind] = latest.copy(children = latest.children + (segment.slot to nextChild))
        return content
    }
    return content.copy(widgets = content.widgets.map { if (it.instanceId == container.instanceId) it.copy(children = it.children + (segment.slot to nextChild)) else it })
}

private fun SlotContent.standingOn(singles: Map<WidgetKind, WidgetInstance>): SlotContent {
    if (widgets.none { it.kind in singles }) return this
    return copy(
        widgets = widgets.map { w ->
            singles[w.kind]?.let { record -> record.copy(placement = w.placement, children = record.children + w.children) } ?: w
        },
    )
}

/**
 * What was edited of each single put into its record, and what is left of it here: that it stands here, where, and
 * what it holds in the slots this config holds as its own, which never reach the record.
 *
 * One that was not here before has arrived, moved or put down, and changes nothing of the record: under the name it
 * goes by it brings as its own the slots it holds otherwise than the record does, and under another name it is a
 * second of what there is one of, the one there is, holding nothing of its own.
 */
private fun SlotContent.settleInto(singles: MutableMap<WidgetKind, WidgetInstance>, before: SlotContent): SlotContent {
    if (widgets.none { it.kind in singles }) return this
    val settled = widgets.map { w ->
        val record = singles[w.kind] ?: return@map w
        if (w.instanceId != record.instanceId) return@map stands(record, w.placement, emptyMap())
        val stood = before.widgets.firstOrNull { it.instanceId == w.instanceId }
            ?: return@map stands(record, w.placement, w.children.filter { (slot, held) -> record.children[slot] != held })
        val own = stood.children.keys
        singles[w.kind] = w.copy(placement = null, children = w.children.filterKeys { it !in own } + record.children.filterKeys { it in own })
        stands(record, w.placement, w.children.filterKeys { it in own })
    }
    val next = copy(widgets = settled)
    return if (next == before) before else next
}

/** [record] standing somewhere: named, placed, holding the slots held there as its own, and nothing of it said twice. */
private fun stands(record: WidgetInstance, placement: Placement?, own: Map<SlotId, SlotContent>): WidgetInstance =
    WidgetInstance(kind = record.kind, instanceId = record.instanceId, placement = placement, children = own)

private fun Composition.stand(content: SlotContent): SlotContent = content.standingOn(singles)

private fun Composition.rootContent(root: NodeRoot): SlotContent? = when (root) {
    is NodeRoot.Layout -> configs[root.config]?.layouts?.firstOrNull { it.id == root.layout }?.root
    NodeRoot.Residents -> residents
    is NodeRoot.Surface -> null
}

private fun Composition.withRoot(root: NodeRoot, content: SlotContent): Composition = when (root) {
    is NodeRoot.Layout -> {
        val config = configs.getValue(root.config)
        val layouts = config.layouts.map { if (it.id == root.layout) it.copy(root = content) else it }
        copy(configs = configs + (root.config to config.copy(layouts = layouts)))
    }
    NodeRoot.Residents -> copy(residents = content)
    // Never reached: a surface's root has no content here, so nothing is changed under it.
    is NodeRoot.Surface -> this
}

/**
 * Moves the widget named [instanceId] from one slot to another, inside one composition or between two. Identity
 * where it would end up inside itself, or where either end is gone. A single does not leave its composition, since
 * it is that composition's one, and it takes along the slots held as its own where it stood.
 */
fun Document.moveWidget(from: NodePath, to: NodePath, instanceId: String, toIndex: Int): Document {
    if (to.nested.any { it.parentInstanceId == instanceId }) return this
    val composition = compositions[from.composition] ?: return this
    val raw = composition.rawContentAt(from) ?: return this
    val widget = raw.widgets.firstOrNull { it.instanceId == instanceId } ?: return this
    val target = contentAt(to) ?: return this
    if (from == to) return edit(from) { it.reordered(it.widgets.indexOfFirst { w -> w.instanceId == instanceId }, toIndex) }
    val single = widget.kind in composition.singles
    if (single && from.composition != to.composition) return this
    return edit(from) { it.withoutWidget(instanceId) }.edit(to) { it.withWidget(target.seated(widget), toIndex) }
}

/**
 * [record] kept as the one widget of its kind [composition] has. Identity where there is one already: what a config
 * puts down a second time is the one that is there.
 */
fun Document.keepSingle(composition: CompositionName, record: WidgetInstance): Document {
    val current = compositions[composition] ?: return this
    if (record.kind in current.singles) return this
    val kept = current.copy(singles = current.singles + (record.kind to record.copy(placement = null)))
    return copy(compositions = compositions + (composition to kept))
}

/**
 * Whether [path] is a slot of a single that its config holds as its own. False for any other slot, and where the path
 * leads nowhere.
 */
fun Document.holdsOwn(path: NodePath): Boolean {
    val (container, slot) = singleSlot(path) ?: return false
    return slot in container.children
}

/**
 * Whether some config of [path]'s composition, in any of its layouts, holds as its own the slot [path] names under a
 * config's root: a slot whose content can change when the composition shows another config.
 */
fun Document.heldOwnAnywhere(path: NodePath): Boolean {
    if (path.root !is NodeRoot.Layout) return false
    val composition = compositions[path.composition] ?: return false
    return composition.configs.any { (id, config) -> config.layouts.any { holdsOwn(path.copy(root = NodeRoot.Layout(id, it.id))) } }
}

/**
 * The document with [path], a slot of a single, held as its config's own from now on: the config holds what the slot
 * held, each widget in it under a name [mint] makes of its name where that name is taken, so the record and the config
 * hold two things and not one thing twice. Identity where the slot is the config's own already, or is no single's.
 */
fun Document.holdOwn(path: NodePath, mint: (String) -> String): Document {
    val (container, slot) = singleSlot(path) ?: return this
    if (slot in container.children) return this
    val composition = compositions.getValue(path.composition)
    val shared = composition.singles.getValue(container.kind).children[slot] ?: return this
    val taken = composition.widgets().map { it.instanceId }.toHashSet()
    fun fresh(name: String): String {
        var next = mint(name)
        while (!taken.add(next)) next = mint(name)
        return next
    }
    fun copied(content: SlotContent): SlotContent = content.copy(
        widgets = content.widgets.map { w ->
            if (w.kind in composition.singles) w else w.copy(instanceId = fresh(w.instanceId), children = w.children.mapValues { (_, c) -> copied(c) })
        },
    )
    return withOwnSlot(path, container.instanceId, slot, copied(shared))
}

/** The document with [path], a slot of a single its config holds as its own, given back to the record. */
fun Document.shareOwn(path: NodePath): Document {
    val (container, slot) = singleSlot(path) ?: return this
    if (slot !in container.children) return this
    return withOwnSlot(path, container.instanceId, slot, null)
}

/** The single [path] is a slot of, as its config holds it, and the slot. Null where [path] is no single's slot. */
private fun Document.singleSlot(path: NodePath): Pair<WidgetInstance, SlotId>? {
    val parent = path.parentPath ?: return null
    val segment = path.nested.last()
    val composition = compositions[path.composition] ?: return null
    val container = composition.rawContentAt(parent)?.widgets?.firstOrNull { it.instanceId == segment.parentInstanceId } ?: return null
    val record = composition.singles[container.kind] ?: return null
    return if (segment.slot in container.children || segment.slot in record.children) container to segment.slot else null
}

private fun Document.withOwnSlot(path: NodePath, instanceId: String, slot: SlotId, content: SlotContent?): Document =
    change(path.parentPath!!) { raw, _ ->
        raw.copy(
            widgets = raw.widgets.map { w ->
                if (w.instanceId != instanceId) w else w.copy(children = if (content == null) w.children - slot else w.children + (slot to content))
            },
        )
    }

/** The document with [config] as the config [id] of [composition], added or put in place of the one there. */
fun Document.withConfig(composition: CompositionName, id: ConfigId, config: Config): Document {
    val current = compositions[composition] ?: return this
    if (current.configs[id] == config) return this
    return copy(compositions = compositions + (composition to current.copy(configs = current.configs + (id to config))))
}

/** The document without the config [id] of [composition]. Identity where there is none. */
fun Document.withoutConfig(composition: CompositionName, id: ConfigId): Document {
    val current = compositions[composition] ?: return this
    if (id !in current.configs) return this
    return copy(compositions = compositions + (composition to current.copy(configs = current.configs - id)))
}

/**
 * The document with every widget [composition] holds, in its singles' records, its configs and its residents, put
 * through [transform], which answers what stands in its place: itself, another, several, or none. A widget's own
 * children go through first, so [transform] sees them as they will be. The records of the singles themselves stay, so a
 * widget the composition has once is not lost to a rewrite of what it holds. Identity where nothing changes.
 */
fun Document.flatMapWidgets(composition: CompositionName, transform: (WidgetInstance) -> List<WidgetInstance>): Document {
    val current = compositions[composition] ?: return this
    fun content(c: SlotContent): SlotContent {
        val next = c.widgets.flatMap { w -> transform(w.copy(children = w.children.mapValues { (_, held) -> content(held) })) }
        return if (next == c.widgets) c else c.copy(widgets = next)
    }
    val rewritten = current.copy(
        singles = current.singles.mapValues { (_, r) -> r.copy(children = r.children.mapValues { (_, held) -> content(held) }) },
        configs = current.configs.mapValues { (_, config) -> config.copy(layouts = config.layouts.map { it.copy(root = content(it.root)) }) },
        residents = content(current.residents),
    )
    return if (rewritten == current) this else copy(compositions = compositions + (composition to rewritten))
}

/** Every widget a composition holds, once each: what stands in its configs and residents, its singles, and what any of them hold. */
fun Composition.widgets(): Sequence<WidgetInstance> = sequence {
    for (record in singles.values) yieldAll(record.withChildren(singles.keys))
    for (config in configs.values) for (layout in config.layouts) yieldAll(layout.root.withChildren(singles.keys))
    yieldAll(residents.withChildren(singles.keys))
    transition?.let { yieldAll(it.withChildren(singles.keys)) }
}

// A single standing somewhere is listed with its record, and what it holds there as its own is listed here.
private fun SlotContent.withChildren(singles: Set<WidgetKind>): Sequence<WidgetInstance> =
    widgets.asSequence().flatMap { if (it.kind in singles) it.held(singles) else it.withChildren(singles) }

private fun WidgetInstance.withChildren(singles: Set<WidgetKind>): Sequence<WidgetInstance> =
    sequenceOf(this) + held(singles)

private fun WidgetInstance.held(singles: Set<WidgetKind>): Sequence<WidgetInstance> =
    children.values.asSequence().flatMap { it.withChildren(singles) }

/**
 * The document with every widget of a composition under a name of its own: the second of two under one name is given
 * the one [mint] makes of it. A file edited by hand can say a name twice, and a widget's state and an edit's address
 * both go by it. Identity where every name is said once.
 */
fun Document.withOwnNames(mint: (String) -> String): Document {
    val next = compositions.mapValues { (_, c) -> c.withOwnNames(mint) }
    return if (next.all { (name, c) -> c === compositions[name] }) this else copy(compositions = next)
}

private fun Composition.withOwnNames(mint: (String) -> String): Composition {
    val names = widgets().map { it.instanceId }.toList()
    if (names.size == names.toSet().size) return this
    val taken = HashSet<String>()
    fun own(name: String): String {
        var next = name
        while (!taken.add(next)) next = mint(name)
        return next
    }
    fun rename(w: WidgetInstance, content: (SlotContent) -> SlotContent): WidgetInstance =
        w.copy(instanceId = own(w.instanceId), children = w.children.mapValues { (_, c) -> content(c) })
    // A single standing somewhere goes by its record's name, whatever that has become.
    fun content(c: SlotContent): SlotContent = c.copy(
        widgets = c.widgets.map { w ->
            if (w.kind in singles) w.copy(children = w.children.mapValues { (_, held) -> content(held) }) else rename(w, ::content)
        },
    )
    val records = singles.mapValues { (_, r) -> rename(r, ::content) }
    val renamed = copy(
        singles = records,
        configs = configs.mapValues { (_, config) -> config.copy(layouts = config.layouts.map { it.copy(root = content(it.root)) }) },
        residents = content(residents),
        transition = transition?.let { rename(it, ::content) },
    )
    return renamed.copy(
        singles = renamed.singles.mapValues { (_, r) -> r.copy(children = r.children.mapValues { (_, c) -> c.named(records) }) },
        configs = renamed.configs.mapValues { (_, config) -> config.copy(layouts = config.layouts.map { it.copy(root = it.root.named(records)) }) },
        residents = renamed.residents.named(records),
    )
}

private fun SlotContent.named(records: Map<WidgetKind, WidgetInstance>): SlotContent = copy(
    widgets = widgets.map { w ->
        val held = w.children.mapValues { (_, c) -> c.named(records) }
        records[w.kind]?.let { w.copy(instanceId = it.instanceId, children = held) } ?: w.copy(children = held)
    },
)
