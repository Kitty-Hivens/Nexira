package hivens.ui.widgets.home.new

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.compose.SubcomposeAsyncImage
import hivens.ui.Screen
import hivens.ui.effects.pixelArtBackground
import hivens.ui.flexible.Flexible
import hivens.ui.flexible.FlexibleKind
import hivens.ui.i18n.LocalStrings
import hivens.ui.nx.InitialsAvatar
import hivens.ui.nx.PlayGround
import hivens.ui.nx.PlayLayout
import hivens.ui.puppet.PuppetClick
import hivens.ui.screens.library.rememberPackArt
import hivens.ui.theme.familyForText
import hivens.ui.theme.decorativePair
import hivens.widget.api.rememberProps
import hivens.widget.model.PropLabel
import hivens.widget.model.PropRange
import hivens.widget.model.Widget
import hivens.widget.model.WidgetInstance
import kotlinx.serialization.Serializable

@Serializable
data class HeroProps(
    /** The height, or with [followWidth] the least height: a wider card is taller than this. */
    @PropLabel("widget.home.new.hero.height") @PropRange(120.0, 340.0) val height: Int = 170,
    @PropLabel("widget.home.new.hero.followWidth") val followWidth: Boolean = true,
    @PropLabel("widget.home.new.hero.showMeta") val showMeta: Boolean = true,
    @PropLabel("widget.home.new.hero.playLayout") val playLayout: PlayLayout = PlayLayout.Plate,
)

// The quicklaunch grown art: a compact "continue playing" card filled with
// the target pack's banner (else the deterministic pixel-art fill). NOT a
// billboard by design -- Home is the user's space, so the card starts small
// and only the user grows it (height prop) or removes it; the full showcase
// lives on the pack detail, which is where a card click goes. The button
// launches. Empty repo elides the widget -- HomeNewRecent owns the CTA.
//
// The card keeps its shape as the window grows: by default its height follows its
// width, so a wide window gets a taller card instead of a longer strip of the same
// 170dp, and the mark and the name grow with it. The height prop is then the floor.
@Widget(
    id = "home.new.hero",
    displayName = "widget.home.new.hero",
    propsClass = HeroProps::class,
    minWidth = 280, minHeight = 140,
    maxWidth = 1600, maxHeight = 640,
)
@Composable
fun HomeNewHero(instance: WidgetInstance) {
    val p = instance.rememberProps<HeroProps>()
    val ctx = LocalHomeNewContext.current
    val s = LocalStrings.current
    val quickLaunch = rememberQuickLaunchTarget(s.homeQuickButton) ?: return
    val target = quickLaunch.target
    val (hueA, hueB) = decorativePair(target.id)
    val art = rememberPackArt(target)

    val eyebrow = if (target.lastPlayedEpochOrZero > 0L) s.homeQuickContinue else s.homeQuickStart
    val openDetail = { ctx.onScreenChange(Screen.PackDetail(target.id)) }

    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        val height = if (p.followWidth) maxOf(p.height.dp, maxWidth / HERO_RATIO).coerceAtMost(HERO_MAX) else p.height.dp
        val tall = height >= TALL_HERO
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .clip(MaterialTheme.shapes.medium)
                .clickable(onClick = openDetail),
        ) {
            Box(Modifier.fillMaxSize().pixelArtBackground(target.id, hueA, hueB))
            if (art.bannerUrl != null) {
                AsyncImage(
                    model              = art.bannerUrl,
                    contentDescription = null,
                    contentScale       = ContentScale.Crop,
                    modifier           = Modifier.fillMaxSize(),
                )
            }
            // Bottom-weighted scrim so the caption row reads over any art.
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.10f),
                        1f to Color.Black.copy(alpha = 0.72f),
                    ),
                ),
            )

            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment     = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                SubcomposeAsyncImage(
                    model              = art.iconUrl,
                    contentDescription = null,
                    contentScale       = ContentScale.Crop,
                    modifier           = Modifier.size(if (tall) 64.dp else 44.dp).clip(RoundedCornerShape(if (tall) 14.dp else 10.dp)),
                    loading            = { Box(Modifier.fillMaxSize().background(hueA)) },
                    error              = { InitialsAvatar(target.displayName, hueA) },
                )
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text       = eyebrow,
                        style      = MaterialTheme.typography.labelSmall,
                        color      = Color.White.copy(alpha = 0.75f),
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        text       = target.displayName,
                        fontFamily = familyForText(target.displayName),
                        style      = if (tall) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge,
                        color      = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        maxLines   = 1,
                        overflow   = TextOverflow.Ellipsis,
                    )
                    if (p.showMeta) {
                        val meta = buildList {
                            target.packRef.version?.let { add(it) }
                            val hours = target.playtimeSeconds / 3600
                            if (hours > 0) add(s.homeHeroPlaytime(hours))
                        }
                        if (meta.isNotEmpty()) {
                            Text(
                                text     = meta.joinToString(" · "),
                                style    = MaterialTheme.typography.bodySmall,
                                color    = Color.White.copy(alpha = 0.8f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                Flexible("home_hero_play_btn", FlexibleKind.Button) {
                    QuickLaunchButton(quickLaunch = quickLaunch, ground = PlayGround.Media, layout = p.playLayout)
                }
            }

            val control = quickLaunch.control
            PuppetClick("home.hero.launch", enabled = control.actionable) { control.onClick() }
            PuppetClick("home.hero.open") { openDetail() }
        }
    }
}

/** Width over height when the card follows its width: a wide strip, not a poster. */
private const val HERO_RATIO = 6f

/** Past this the card would take the screen it is meant to sit on. */
private val HERO_MAX = 420.dp

/** From this height the mark and the name step up a size. */
private val TALL_HERO = 230.dp
