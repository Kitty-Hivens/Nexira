package hivens.ui.notifications

// Stable icon identifier for notifications that carry no source-avatar URL --
// launcher-generated events (an available update, and future system messages)
// that are not tied to a pack's `icon_url`. The renderer maps each to a vector
// glyph; kotlinx serializes by name, so a history reload keeps the icon.
enum class NotifGlyph {
    Update,
}
