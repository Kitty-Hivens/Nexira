package hivens.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import hivens.ui.puppet.PuppetScreen
import hivens.widget.api.SlotRenderer
import hivens.widget.model.SlotId
import hivens.widget.model.SurfaceId

/**
 * Widget-composed home surface. Kernel-3 ships a minimal prototype --
 * welcome banner + recent-packs row + quick-launch -- proving the
 * slot machinery for a brand-new (rather than legacy-wrapped) surface.
 * Content grows in later phases as user-customization arrives.
 */
@Composable
fun NewHomeScreen() {
    PuppetScreen("NewHome")

    // The context Home's widgets read is the shell's now (see AppLayout), so a Home
    // widget works on any surface it is dropped on.
    //
    // Whether Home scrolls is the slot's own viewport, static unless the reader set
    // it otherwise, and the kernel measures each widget for it.
    // No blanket slot padding. Spacing between widgets a flow still owns, but the
    // margin from the window edge is each widget's own now, carried on its
    // placement, so a widget that wants to reach the edge can and a widget that
    // wants room says so. The bundled layout seeds the gutter it used to get here.
    SlotRenderer(
        SurfaceId(SURFACE),
        SlotId("main"),
        modifier = Modifier.fillMaxSize(),
        spacing  = 8.dp,
    )
}

private const val SURFACE = "home.new"
