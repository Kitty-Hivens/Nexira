package hivens.launcher.component

import hivens.core.api.interfaces.IManifestProcessorService
import hivens.core.data.FileManifest
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors

/**
 * Компонент, реализующий стратегию формирования Classpath для JVM.
 *
 * Отвечает за сборку корректного аргумента `-classpath` с учетом специфических требований
 * серверного античита (проверка контрольных сумм библиотек).
 *
 * **Конфигурация путей:**
 * Все библиотеки ищутся строго внутри папки клиента (например, `.aura/clients/RPG/libraries`),
 * обеспечивая полную изоляцию сборок.
 *
 * **Особенности реализации для обхода защиты:**
 * * **Отключена фильтрация по ОС:** Загружаются нативные библиотеки всех платформ,
 * так как сервер проверяет хеш полного списка библиотек из манифеста.
 * * **Фильтрация служебного мусора:** Исключаются локальные архивы Aura (`assets.zip`, `extra.zip`),
 * которые отсутствуют в официальном клиенте и ломают хеш.
 */
internal class ClasspathProvider(
    private val manifestProcessor: IManifestProcessorService
) {

    /**
     * Строит строку путей к классам (Classpath String).
     *
     * **Алгоритм работы:**
     * 1. Агрегация путей из манифеста файлов.
     * 2. Разрешение всех путей относительно корня клиента (`clientRoot`).
     * 3. Мягкая проверка существования файлов.
     * 4. Фильтрация "мусорных" архивов Aura.
     * 5. Сортировка и выделение главного JAR-файла.
     *
     * @param clientRoot Корневая директория экземпляра клиента (например, .../clients/RPG).
     * @param manifest Объект манифеста, содержащий список ожидаемых файлов.
     * @param excludedModules Список исключаемых модулей (не используется в данной реализации).
     *
     * @return Строка путей, разделенная системным разделителем [File.pathSeparator].
     */
    fun buildClasspath(
        clientRoot: Path,
        manifest: FileManifest,
        excludedModules: List<String>
    ): String {
        val allJars = HashSet<Path>()
        val modsDir = clientRoot.resolve("mods").toAbsolutePath()

        // 1. Агрегация и разрешение путей
        // ВАЖНО: Все пути строятся относительно папки клиента (Local Libraries Strategy)
        manifestProcessor.flattenManifest(manifest).keys.forEach { rawPath ->
            val cleanPath = sanitizePath(rawPath)
            // Всегда resolve от clientRoot, никаких externalRoot
            val resolvedPath = clientRoot.resolve(cleanPath)
            allJars.add(resolvedPath)
        }

        val libs = ArrayList<String>()
        var mainJar: String? = null

        // 2. Фильтрация и формирование списка
        allJars.forEach { path ->
            val pathStr = path.toAbsolutePath().toString()
            val fileName = path.fileName.toString().lowercase()

            // --- Блок валидации ---

            // Пропускаем файл, только если его нет физически И это не библиотека.
            if (!Files.exists(path) && !pathStr.contains("libraries")) return@forEach

            // Проверка расширений и системных папок
            if (!fileName.endsWith(".jar") && !fileName.endsWith(".zip")) return@forEach
            if (pathStr.startsWith(modsDir.toString())) return@forEach
            if (pathStr.contains("/config/") || pathStr.contains("/assets/")) return@forEach

            // --- Блок "Clean Aura" (Фильтр мусора) ---
            // Убираем файлы, которых нет в манифесте сервера
            if ((fileName.startsWith("assets-") || fileName == "assets.zip") && fileName.endsWith(".zip")) return@forEach
            if ((fileName.startsWith("natives-") || fileName == "natives.zip") && fileName.endsWith(".zip")) return@forEach
            if (fileName == "extra.zip") return@forEach

            // --- Блок Cross-Platform ---
            // Фильтр по ОС отключен намеренно. Грузим всё для совпадения хеша.

            // --- Блок разделения ---
            if (fileName.startsWith("smartycraft") && fileName.endsWith(".jar")) {
                mainJar = pathStr
            } else {
                libs.add(pathStr)
            }
        }

        // 3. Сортировка (для детерминированного порядка)
        libs.sort()

        // Добавляем Main Jar в конец списка
        val result = if (mainJar != null) {
            libs + mainJar!!
        } else {
            libs
        }

        return result.stream().collect(Collectors.joining(File.pathSeparator))
    }

    /**
     * Очищает "сырой" путь из манифеста от лишних префиксов.
     *
     * Убирает первую директорию из пути, если она не является стандартной.
     */
    private fun sanitizePath(rawPath: String): String {
        val parts = rawPath.split("/")
        if (parts.size < 2) return rawPath

        val firstDir = parts[0]
        if (!firstDir.startsWith("libraries") && !firstDir.equals("bin") && !firstDir.equals("mods")) {
            return rawPath.substring(firstDir.length + 1)
        }
        return rawPath
    }
}