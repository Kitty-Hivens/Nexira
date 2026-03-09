# Aura Launcher (Unofficial)

<div align="center">

![Version](https://img.shields.io/badge/version-1.2.3--dev-blueviolet?style=for-the-badge)
![Kotlin](https://img.shields.io/badge/Kotlin-2.3.0-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)
![Compose](https://img.shields.io/badge/Compose-Multiplatform-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white)
![License](https://img.shields.io/badge/License-GPLv3-green?style=for-the-badge)

<br>

[ 🇬🇧 English ](README.md) | **[ 🇷🇺 Русский ]** | [ 🇩🇪 Deutsch ](README_DE.md)

</div>

> **Неофициальный** лаунчер для проекта [SmartyCraft](https://smartycraft.ru).
> Создан как легкая, быстрая и открытая альтернатива официальному клиенту.

**Aura Launcher** — это больше, чем просто кнопка «Играть». Это современный дом для вашей игры, написанный с нуля на **Kotlin** с использованием **Compose Multiplatform**.

Мы считаем, что запуск любимого сервера не должен напоминать работу с артефактом из 2013 года. В отличие от оригинала, Aura не тащит за собой древние версии Java, запускается мгновенно и уважает вашу операционную систему — будь то Linux, Windows или macOS.

## ✨ Почему Aura?

Мы создали Aura, потому что любим эффективный софт.

* **Легкость:** Ничего лишнего. Интерфейс работает на **Compose Desktop**, оставаясь отзывчивым и экономным к ресурсам.
* **Атмосфера:** Лаунчер живет вместе с вами. Динамические **сезонные темы** — от мягкого зимнего снегопада до летних светлячков — автоматически подстраиваются под время года (или ваше настроение).
* **Linux First:** Мы не считаем пользователей Linux гражданами второго сорта. Aura предлагает нативный опыт: поддержка AppImage, бесшовная работа в Wayland и никаких «танцев с бубном» для настройки Java.
* **Прозрачность и Открытость:** Вы заслуживаете знать, что запускается на вашем компьютере. Наш код на 100% открыт под лицензией GPLv3.

---

## 📚 Документация

Для разработчиков, контрибьюторов и просто любопытных.
Мы поддерживаем подробную техническую документацию, охватывающую архитектуру, сезонный движок и сетевой стек.

👉 **[Открыть Вики Разработчика](docs/ru/index.md)**

---

## 🚀 Установка

Загружайте последнюю версию в разделе **[Releases](https://github.com/Kitty-Hivens/Aura-Launcher/releases)**.

### 🐧 Linux
Мы заботимся о вашем комфорте.
* **AppImage:** Универсальный ключ. Просто скачайте, сделайте файл исполняемым (`chmod +x AuraLauncher.AppImage`) и запускайте.
* **Нативные пакеты:** `.deb` (Debian/Ubuntu/Mint) и `.rpm` (Fedora/RedHat/OpenSUSE) для полной интеграции с системой.

### 🪟 Windows
* Скачайте и запустите `.msi` установщик.
* Aura сама подготовит нужную Java для игры. Вам ничего настраивать не нужно.

### 🍎 macOS
* Скачайте `.dmg` образ.
* Перетащите приложение в папку `Applications`.
* *Поддержка как Intel, так и Apple Silicon.*

## 🛠️ Сборка из исходников

Для тех, кто любит ковыряться внутри.
Вам понадобится **JDK 25** или выше.

1.  **Клонируйте репозиторий:**
    ```bash
    git clone https://github.com/Kitty-Hivens/Aura-Launcher.git
    cd Aura-Launcher
    ```

2.  **Соберите дистрибутив:**
    * **Linux / macOS:**
        ```bash
        ./gradlew :client-ui:packageDistribution
        ```
    * **Windows:**
        ```bash
        gradlew.bat :client-ui:packageDistribution
        ```

3.  **Готовые файлы:**
    Ваша свежая сборка будет ждать вас в папке:
    `client-ui/build/compose/binaries/main/`

## ⚖️ Дисклеймер

Этот проект является **неофициальным программным обеспечением**.
Разработчик Aura Launcher никак не связан с администрацией SmartyCraft.
Все права на контент серверов, моды и торговые марки принадлежат их законным владельцам.

## 📄 Лицензия

Проект распространяется под лицензией **GNU GPL v3**.
Это гарантирует, что Aura (и любые её модификации) всегда будет оставаться свободной и с открытым исходным кодом.

---

<div align="center">
  <i>Сделано с 💜 и Kotlin разработчиком <a href="https://github.com/Kitty-Hivens">Kitty-Hivens</a></i>
</div>
