package hivens.widget.model

/** Where in a composition a path starts. */
sealed interface NodeRoot {
    /** The root of one layout of a config. */
    data class Layout(val config: ConfigId, val layout: LayoutId) : NodeRoot

    /** The composition's residents. */
    data object Residents : NodeRoot
}

/**
 * The address of one slot's content in a [Document]: where it starts and the containers descended through.
 *
 * A path that descends through one of the composition's singles addresses that widget's one record, whichever
 * config it was reached by, so an edit made through one config is seen in all of them.
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
    var content = composition.rootContent(path.root) ?: return null
    for (segment in path.nested) {
        val container = content.widgets.firstOrNull { it.instanceId == segment.parentInstanceId } ?: return null
        content = composition.standing(container).children[segment.slot] ?: return null
    }
    return composition.stand(content)
}

/**
 * The document with the content at [path] replaced by what [edit] makes of it.
 *
 * [edit] is given the content with every single in it as it stands, and what it changes of one goes to the record
 * kept once for the composition. Where it stands stays the config's own, so a single is moved in one config and
 * changed in all. Returns this very document where the path leads nowhere or [edit] changes nothing, since an edit
 * races with a document being replaced and one on what is gone must not fail.
 */
fun Document.edit(path: NodePath, edit: (SlotContent) -> SlotContent): Document {
    val composition = compositions[path.composition] ?: return this
    val root = composition.rootContent(path.root) ?: return this
    val singles = composition.singles.toMutableMap()
    val next = editContent(root, path.nested, singles, edit)
    if (next === root && singles == composition.singles) return this
    return copy(compositions = compositions + (path.composition to composition.copy(singles = singles).withRoot(path.root, next)))
}

private fun editContent(
    content: SlotContent,
    nested: List<NestedSegment>,
    singles: MutableMap<WidgetKind, WidgetInstance>,
    edit: (SlotContent) -> SlotContent,
): SlotContent {
    if (nested.isEmpty()) {
        val standing = content.standingOn(singles)
        val edited = edit(standing)
        if (edited === standing) return content
        return edited.settleInto(singles, content)
    }
    val segment = nested.first()
    val container = content.widgets.firstOrNull { it.instanceId == segment.parentInstanceId } ?: return content
    val record = singles[container.kind] ?: container
    val child = record.children[segment.slot] ?: return content
    val nextChild = editContent(child, nested.drop(1), singles, edit)
    if (nextChild === child) return content
    // Read again: an edit further down may have changed this very record, a single inside a single of its own kind say.
    val latest = singles[container.kind] ?: container
    val nextRecord = latest.copy(children = latest.children + (segment.slot to nextChild))
    if (container.kind in singles) {
        singles[container.kind] = nextRecord
        return content
    }
    return content.copy(widgets = content.widgets.map { if (it.instanceId == container.instanceId) nextRecord else it })
}

private fun SlotContent.standingOn(singles: Map<WidgetKind, WidgetInstance>): SlotContent {
    if (widgets.none { it.kind in singles }) return this
    return copy(widgets = widgets.map { w -> singles[w.kind]?.copy(placement = w.placement) ?: w })
}

/**
 * What was edited of each single put into its record, and what is left of it here: that it stands here, and where.
 * One put down under another name is a second of what there is one of, and is the one there is: it changes nothing.
 */
private fun SlotContent.settleInto(singles: MutableMap<WidgetKind, WidgetInstance>, before: SlotContent): SlotContent {
    if (widgets.none { it.kind in singles }) return this
    val settled = widgets.map { w ->
        val record = singles[w.kind] ?: return@map w
        if (w.instanceId == record.instanceId) singles[w.kind] = w.copy(placement = null)
        stands(record, w.placement)
    }
    val next = copy(widgets = settled)
    return if (next == before) before else next
}

/** [record] standing somewhere: named and placed, and nothing of it said twice. */
private fun stands(record: WidgetInstance, placement: Placement?): WidgetInstance =
    WidgetInstance(kind = record.kind, instanceId = record.instanceId, placement = placement)

private fun Composition.stand(content: SlotContent): SlotContent = content.standingOn(singles)

private fun Composition.rootContent(root: NodeRoot): SlotContent? = when (root) {
    is NodeRoot.Layout -> configs[root.config]?.layouts?.firstOrNull { it.id == root.layout }?.root
    NodeRoot.Residents -> residents
}

private fun Composition.withRoot(root: NodeRoot, content: SlotContent): Composition = when (root) {
    is NodeRoot.Layout -> {
        val config = configs.getValue(root.config)
        val layouts = config.layouts.map { if (it.id == root.layout) it.copy(root = content) else it }
        copy(configs = configs + (root.config to config.copy(layouts = layouts)))
    }
    NodeRoot.Residents -> copy(residents = content)
}

/**
 * Moves the widget named [instanceId] from one slot to another, inside one composition or between two. Identity
 * where it would end up inside itself, or where either end is gone. A single does not leave its composition, since
 * it is that composition's one.
 */
fun Document.moveWidget(from: NodePath, to: NodePath, instanceId: String, toIndex: Int): Document {
    if (to.nested.any { it.parentInstanceId == instanceId }) return this
    val source = contentAt(from) ?: return this
    val widget = source.widgets.firstOrNull { it.instanceId == instanceId } ?: return this
    val target = contentAt(to) ?: return this
    if (from == to) return edit(from) { it.reordered(it.widgets.indexOfFirst { w -> w.instanceId == instanceId }, toIndex) }
    val single = widget.kind in compositions.getValue(from.composition).singles
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

/** Every widget a composition holds, once each: what stands in its configs and residents, its singles, and what any of them hold. */
fun Composition.widgets(): Sequence<WidgetInstance> = sequence {
    for (record in singles.values) yieldAll(record.withChildren(singles.keys))
    for (config in configs.values) for (layout in config.layouts) yieldAll(layout.root.withChildren(singles.keys))
    yieldAll(residents.withChildren(singles.keys))
    transition?.let { yieldAll(it.withChildren(singles.keys)) }
}

private fun SlotContent.withChildren(singles: Set<WidgetKind>): Sequence<WidgetInstance> =
    widgets.asSequence().filter { it.kind !in singles }.flatMap { it.withChildren(singles) }

private fun WidgetInstance.withChildren(singles: Set<WidgetKind>): Sequence<WidgetInstance> =
    sequenceOf(this) + children.values.asSequence().flatMap { it.withChildren(singles) }

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
    fun content(c: SlotContent): SlotContent =
        c.copy(widgets = c.widgets.map { w -> if (w.kind in singles) w else rename(w, ::content) })
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
        records[w.kind]?.let { w.copy(instanceId = it.instanceId) }
            ?: w.copy(children = w.children.mapValues { (_, c) -> c.named(records) })
    },
)
