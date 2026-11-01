package hivens.ui.widgets.home.new

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import hivens.ui.Screen
import hivens.ui.effects.pixelArtBackground
import hivens.ui.i18n.LocalStrings
import hivens.ui.nx.NxMetaChipTone
import hivens.ui.nx.PlayGround
import hivens.ui.nx.PlayLayout
import hivens.ui.screens.library.PendingUpdateBadge
import hivens.ui.screens.library.lastPlayedLabel
import hivens.ui.screens.library.rememberPackArt
import hivens.ui.feature.catalogue.project.loaderLabel
import hivens.ui.theme.NxInk
import hivens.ui.theme.decorativePair
import hivens.ui.theme.familyForText
import hivens.widget.api.rememberProps
import hivens.widget.model.PropLabel
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import kotlinx.serialization.Serializable

@Serializable
data class ContinueProps(
    @PropLabel("widget.home.new.continue.showFacts") val showFacts: Boolean = true,
    @PropLabel("widget.home.new.hero.playLayout") val playLayout: PlayLayout = PlayLayout.Plate,
)

// The pack to go back to, said in type rather than shown as a picture.
//
// Most packs have no art, and a picture of a pack with none is its pixel fill blown
// up to a banner, which reads as noise. So the name carries it: set at display size,
// growing with the width it is given, beside the pack's mark (its icon when it has
// one, a strip of its fill when it does not). Under it the facts as numerals, how long
// it has been played, when it was last, what it runs on, read at a glance rather than
// parsed out of a caption. Play is the one button. The name opens the pack.
//
// Empty library elides the widget: the pack list owns the call to action then.
@Widget(
    id = "home.new.continue",
    enter = "rise",
    displayName = "widget.home.new.continue",
    propsClass = ContinueProps::class,
    surface = """{"fill":"panel"}""",
    minWidth = 280, minHeight = 160,
    maxWidth = 2400, maxHeight = 640,
)
@Composable
fun HomeNewContinue(instance: WidgetInstance) {
    val p = instance.rememberProps<ContinueProps>()
    val ctx = LocalHomeNewContext.current
    val s = LocalStrings.current
    val quickLaunch = rememberQuickLaunchTarget(s.homeQuickButton) ?: return
    val pack = quickLaunch.target
    val art = rememberPackArt(pack)
    val (hueA, hueB) = decorativePair(pack.id)
    val openDetail = { ctx.onScreenChange(Screen.PackDetail(pack.id)) }

    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 28.dp)) {
        // The name is the picture here, so it takes its size from the room it has:
        // a wide window sets it large, a narrow slot still sets it as a heading.
        val nameSize = (maxWidth.value / NAME_DIVISOR).coerceIn(NAME_MIN, NAME_MAX)
        val markHeight = (nameSize * MARK_RATIO).dp
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text       = if (pack.lastPlayedEpochOrZero > 0L) s.homeQuickContinue else s.homeQuickStart,
                    style      = MaterialTheme.typography.labelLarge,
                    color      = NxInk.quiet,
                    modifier   = Modifier.weight(1f),
                )
                PendingUpdateBadge(pack.id, tone = NxMetaChipTone.Surface)
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                if (art.iconUrl != null) {
                    AsyncImage(
                        model              = art.iconUrl,
                        contentDescription = null,
                        contentScale       = ContentScale.Crop,
                        modifier           = Modifier.size(markHeight).clip(RoundedCornerShape(markHeight / 5)),
                    )
                } else {
                    Box(
                        Modifier.size(width = MARK_STRIP, height = markHeight)
                            .clip(RoundedCornerShape(MARK_STRIP / 2))
                            .pixelArtBackground(pack.id, hueA, hueB),
                    )
                }
                Text(
                    text       = pack.displayName,
                    fontFamily = familyForText(pack.displayName),
                    fontSize   = nameSize.sp,
                    lineHeight = (nameSize * 1.06f).sp,
                    fontWeight = FontWeight.Bold,
                    color      = NxInk.main,
                    maxLines   = 1,
                    overflow   = TextOverflow.Ellipsis,
                    modifier   = Modifier.clickable(onClick = openDetail),
                )
            }
            Spacer(Modifier.height(22.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                if (p.showFacts) {
                    FlowRow(
                        modifier              = Modifier.weight(1f),
                        horizontalArrangement = Arrangement.spacedBy(44.dp),
                        verticalArrangement   = Arrangement.spacedBy(12.dp),
                    ) {
                        val hours = pack.playtimeSeconds / 3600
                        if (hours > 0) Numeral(s.homeFactHours(hours), s.homeFactPlaytime)
                        Numeral(lastPlayedLabel(pack.lastPlayedEpochOrZero), s.homeFactLastSession)
                        pack.cachedManifest?.let { m ->
                            if (m.minecraftVersion.isNotBlank()) {
                                Numeral(m.minecraftVersion, loaderLabel(m.loaderName.ifBlank { "vanilla" }))
                            }
                        }
                    }
                } else {
                    Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.width(24.dp))
                QuickLaunchButton(quickLaunch = quickLaunch, ground = PlayGround.Surface, layout = p.playLayout)
            }
        }
    }
}

/** A fact read at a glance: the value set large, what it is set small under it. */
@Composable
internal fun Numeral(value: String, label: String) {
    Column {
        Text(
            text       = value,
            fontSize   = 30.sp,
            lineHeight = 32.sp,
            fontWeight = FontWeight.SemiBold,
            color      = NxInk.main,
            maxLines   = 1,
        )
        Text(
            text  = label,
            style = MaterialTheme.typography.labelMedium,
            color = NxInk.quiet,
            maxLines = 1,
        )
    }
}

/** Width over name size: at 1500dp of room the name is set at about 72sp. */
private const val NAME_DIVISOR = 21f
private const val NAME_MIN = 34f
private const val NAME_MAX = 76f

/** The mark beside the name stands as tall as the name is set. */
private const val MARK_RATIO = 1.0f

/** Width of the fill strip that stands in for a mark when the pack has no icon. */
private val MARK_STRIP = 10.dp
