package hivens.pages.api

import hivens.widget.api.ModuleContribution
import hivens.widget.model.WidgetKind

/**
 * A screen a module brings. The pages host shows it under [id] and draws it with the widget [kind] where its own
 * record holds nothing for it. [label] is a key into the module's words, what lists of screens call it, and [icon] a
 * Material Symbols name or null.
 */
class ScreenContribution(
    override val id: String,
    override val label: String,
    val kind: WidgetKind,
    val icon: String? = null,
) : ModuleContribution
