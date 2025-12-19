package hivens.core.api.interfaces

import hivens.core.api.model.ServerProfile
import hivens.core.data.FileData
import hivens.core.data.FileManifest
import hivens.core.data.OptionalMod

interface IManifestProcessorService {

    fun processManifest(version: String): FileManifest?

    /**
     * Рекурсивно "выпрямляет" (flattens) древовидный манифест файлов.
     * @return Плоская карта путь -> данные.
     */
    fun flattenManifest(manifest: FileManifest): Map<String, FileData>

    /**
     * Возвращает список опциональных модификаций для конкретного профиля.
     */
    fun getOptionalModsForClient(profile: ServerProfile): List<OptionalMod>
}
