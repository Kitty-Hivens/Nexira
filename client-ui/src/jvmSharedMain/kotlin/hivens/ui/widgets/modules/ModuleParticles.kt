package hivens.ui.widgets.modules

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import hivens.ui.effects.Mote
import hivens.ui.effects.NxParticleField
import hivens.ui.effects.ParticleDensity
import hivens.ui.effects.ParticleRule
import hivens.ui.widgets.modules.WidgetModules.Contributed
import hivens.widget.api.ParticleContribution
import hivens.widget.api.ParticlePalette
import hivens.widget.api.WidgetDescriptor
import hivens.widget.api.WidgetRegistry
import hivens.widget.api.rememberProps
import hivens.widget.api.widgetPropsJson
import hivens.widget.model.PropLabel
import hivens.widget.model.WidgetInstance
import hivens.widget.model.WidgetKind
import hivens.widget.model.WidgetSizing
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/** What a person sets on a particle field a module brought: how thick it is. The field itself is the module's. */
@Serializable
data class ModuleParticleProps(
    @PropLabel("widget.decor.particles.density") val density: ParticleDensity = ParticleDensity.Even,
)

/**
 * The particle fields modules brought, as widgets.
 *
 * A field becomes a widget of the module's own, `<module>.particles.<id>`, so it is placed from the
 * palette, sized, layered and saved like any other, and a layout that holds one keeps it while the
 * module is off. Nothing about particles is special to the shell past this.
 */
class ModuleParticleWidgets(fields: List<Contributed<ParticleContribution>>) : WidgetRegistry {

    private val widgets: Map<WidgetKind, WidgetDescriptor> =
        fields.associate { entry -> kindOf(entry) to ParticleWidgetDescriptor(kindOf(entry), entry.item) }

    override fun all(): Map<WidgetKind, WidgetDescriptor> = widgets

    override fun get(kind: WidgetKind): WidgetDescriptor? = widgets[kind]

    companion object {
        fun kindOf(entry: Contributed<ParticleContribution>): WidgetKind = WidgetKind("${entry.moduleId}.particles.${entry.item.id}")
    }
}

private class ParticleWidgetDescriptor(override val kind: WidgetKind, private val field: ParticleContribution) : WidgetDescriptor {
    override val displayName: String = field.label
    override val removable: Boolean = true
    override val propsSerializer = ModuleParticleProps.serializer()
    override val defaultPropsJson: JsonObject =
        widgetPropsJson.encodeToJsonElement(ModuleParticleProps.serializer(), ModuleParticleProps()).jsonObject
    override val sizing: WidgetSizing = WidgetSizing(minWidth = 80, minHeight = 80)
    private val rule = ModuleParticleRule(field)

    @Composable
    override fun Render(instance: WidgetInstance) {
        val props = instance.rememberProps<ModuleParticleProps>()
        NxParticleField(field = rule, density = props.density, modifier = Modifier.fillMaxSize())
    }
}

/** A module's field as the rule the particle layer draws, converting each mote the module answers with. */
private class ModuleParticleRule(private val field: ParticleContribution) : ParticleRule {
    override fun palette(ink: Color, quiet: Color, colors: List<Color>): List<Color> =
        field.palette(ParticlePalette(ink, quiet, colors)).ifEmpty { listOf(ink) }

    override fun moteAt(i: Int, t: Float, w: Float, h: Float): Mote {
        val mote = field.moteAt(i, t, w, h)
        return Mote(mote.x, mote.y, mote.radius, mote.alpha, mote.colour)
    }
}
