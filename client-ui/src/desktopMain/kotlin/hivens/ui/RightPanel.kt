package hivens.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import hivens.core.data.SessionData
import hivens.ui.widgets.shell.LocalRightRailContext
import hivens.ui.widgets.shell.RightRailContext
import hivens.widget.api.SlotRenderer
import hivens.widget.api.SurfaceFamilyHost
import hivens.widget.model.FamilyId
import hivens.widget.model.SlotId
import hivens.widget.model.SurfaceId

/**
 * Right-side panel. Surface composable: owns the column container; what fills it
 * resolves through SlotRenderer against the layout graph.
 *
 * The rail has two families, and which one shows is the app's to say. [RailFamily.GENERAL]
 * is the rail at rest -- the feed, and the message history pinned under it.
 * [RailFamily.PROJECT_VIEW] is the rail while something is open in the middle:
 * its slots are about that thing, so they are different slots and not the same
 * ones rearranged, which is why the branch is here in code rather than in the
 * graph. Sign-in moved to the Profile section, so neither family carries an auth
 * slot.
 */
@Composable
fun RightPanel(
    appState: AppState,
    onLogin: (SessionData) -> Unit,
    onLogout: () -> Unit,
    sslBypass: Boolean,
    modifier: Modifier = Modifier,
) {
    val ctx = remember(appState, onLogin, onLogout, sslBypass) {
        RightRailContext(
            appState  = appState,
            onLogin   = onLogin,
            onLogout  = onLogout,
            sslBypass = sslBypass,
        )
    }
    val surface = SurfaceId(SURFACE)
    CompositionLocalProvider(LocalRightRailContext provides ctx) {
        SurfaceFamilyHost(surface) { family ->
            // Hoisted out of the branch below: a remembered value created inside an
            // `if` is thrown away the moment the condition flips, and the branch
            // here flips every time the reader opens or leaves a project.
            val scroll = rememberScrollState()
            // The blocks carry their own plane and their own padding now, so the
            // column only has to hold them off the window edge and apart from each
            // other. Measured off the reference: a 12 gutter between cards, and the
            // rail's own 12 to the edge on both sides.
            val inset = if (family == RailFamily.PROJECT_VIEW) {
                Modifier.verticalScroll(scroll).padding(horizontal = RAIL_GUTTER, vertical = RAIL_GUTTER)
            } else {
                Modifier
            }
            Column(
                modifier = modifier.then(inset),
                verticalArrangement = if (family == RailFamily.PROJECT_VIEW) {
                    Arrangement.spacedBy(RAIL_GUTTER)
                } else {
                    Arrangement.Top
                },
            ) {
                when (family) {
                    RailFamily.PROJECT_VIEW -> {
                        // Both stack from the top, and neither takes the weight.
                        // The general family below gives it to the feed so the
                        // message history stays pinned to the bottom edge, which is
                        // right for a feed and wrong here: it drove the author's
                        // links to the floor with eight hundred pixels of nothing
                        // above them, as if they belonged to a different panel.
                        // A project's blocks are one column read downward.
                        SlotRenderer(surface, SlotId("modData"), Modifier.fillMaxWidth(), spacing = RAIL_GUTTER)
                        SlotRenderer(surface, SlotId("authorData"), Modifier.fillMaxWidth(), spacing = RAIL_GUTTER)
                    }
                    // General, and anything a later build named that this one has
                    // never heard of: the rail at rest is the safe thing to draw,
                    // and drawing nothing would be a blank pane with no way back.
                    else -> {
                        SlotRenderer(surface, SlotId("news"), Modifier.weight(1f).fillMaxWidth())
                        // Bottom slot: the message-history widget seeds here by
                        // default; the news slot takes the weight so this stays
                        // pinned to the bottom.
                        SlotRenderer(surface, SlotId("bottom"), Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

/** The families [RightPanel] knows how to draw. */
object RailFamily {
    val GENERAL: FamilyId = FamilyId.GENERAL
    val PROJECT_VIEW: FamilyId = FamilyId("projectView")
}

/**
 * The rail's own spacing unit, between cards and from the window edge.
 *
 * One number rather than three, because a card's gutter and the gap to the next
 * card are the same measurement in the reference and drifting them apart is what
 * makes a column of cards read as a list of unrelated panes.
 */
private val RAIL_GUTTER = 12.dp

private const val SURFACE = "appshell.rightrail"

/** The right rail's surface id, for the code that switches its family. */
val RIGHT_RAIL_SURFACE: SurfaceId = SurfaceId(SURFACE)
