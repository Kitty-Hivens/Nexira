package hivens.themes

import hivens.widget.api.ServiceContext

/** On a desktop, the scheme the desktop's own settings answer, asked of the system's tools ([SystemTheme]). */
internal actual fun platformSchemeReader(context: ServiceContext): SystemSchemeReader = SystemTheme
