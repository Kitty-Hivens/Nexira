package hivens.core.api.interfaces

import hivens.core.data.SettingsData

interface ISettingsService {
    /**
     * Возвращает текущие настройки (из кэша памяти).
     * Если не загружены — загружает.
     */
    fun getSettings(): SettingsData

    /**
     * Сохраняет настройки на диск.
     */
    fun saveSettings(settings: SettingsData)
}
