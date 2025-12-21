package hivens.core.data

/**
 * Профиль настроек конкретного сервера.
 * Хранит выбор игрока и локальные настройки.
 */
data class InstanceProfile( // TODO: Решить, что делать с дефолтными настройками в ui.
    var serverId: String = "",
    var memoryMb: Int = 4096,
    var javaPath: String? = null,
    var jvmArgs: String? = null,
    var windowWidth: Int = 925,
    var windowHeight: Int = 530,
    var fullScreen: Boolean = false,
    var autoConnect: Boolean = true,
    var optionalModsState: MutableMap<String, Boolean> = HashMap()
)
