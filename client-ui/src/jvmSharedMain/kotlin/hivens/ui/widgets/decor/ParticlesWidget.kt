package hivens.ui.widgets.decor

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import hivens.ui.effects.NxParticleField
import hivens.ui.effects.ParticleDensity
import hivens.ui.effects.ParticleField
import hivens.widget.api.rememberProps
import hivens.widget.model.PropLabel
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import kotlinx.serialization.Serializable

@Serializable
data class ParticlesProps(
    @PropLabel("widget.decor.particles.field") val field: ParticleField = ParticleField.Dust,
    @PropLabel("widget.decor.particles.density") val density: ParticleDensity = ParticleDensity.Even,
)

// A field of particles filling whatever it is given, in the theme's colours. Made
// for the backdrop layer under the content pane, where it shows in the gaps between
// panels and over the wallpaper, and it fills a slot anywhere else the same way.
//
// Decor, so it is never there unless somebody puts it there: it draws every frame,
// and that cost is a choice a person makes rather than one shipped to everyone.
@Widget(
    id = "decor.particles",
    displayName = "widget.decor.particles",
    propsClass = ParticlesProps::class,
    minWidth = 80, minHeight = 80,
)
@Composable
fun ParticlesWidget(instance: WidgetInstance) {
    val p = instance.rememberProps<ParticlesProps>()
    NxParticleField(field = p.field, density = p.density, modifier = Modifier.fillMaxSize())
}
