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
     */
    fun saveSettings(settings: SettingsData)
}
