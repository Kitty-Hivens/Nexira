package hivens.core.api.interfaces

import hivens.core.data.SettingsData

interface ISettingsService {
    /**
     * Returns current settings (from memory cache).
     * If not loaded, it loads.
     */
    fun getSettings(): SettingsData

    /**
     * Saves settings to disk.
     *
     * Whole, as given. A caller that means to change some fields wants
     * [updateSettings]: read here, changed and passed back, the object overwrites
     * whatever another writer saved in between.
     */
    fun saveSettings(settings: SettingsData)

    /**
     * Applies [transform] to the settings as they stand and saves the result, as
     * one step, and returns what was saved.
     *
     * The settings are written from several places at once (the player's volume,
     * a theme flip, the settings screen, a sign-in), and a read followed by a save
     * let the slower of two writers put back a field the other had just changed.
     * The default here is that read and save for test fakes. The real store runs
     * it under its own lock.
     */
    fun updateSettings(transform: (SettingsData) -> SettingsData): SettingsData {
        val next = transform(getSettings())
        saveSettings(next)
        return next
    }
}
