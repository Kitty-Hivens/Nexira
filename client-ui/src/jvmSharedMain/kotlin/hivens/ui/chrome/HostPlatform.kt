package hivens.ui.chrome

/** True on macOS, where caption buttons live at the LEFT (traffic-light side). */
val HOST_IS_MAC: Boolean = System.getProperty("os.name").orEmpty().lowercase().contains("mac")
