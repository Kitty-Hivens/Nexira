# Aura Launcher (Unofficial)

<div align="center">

![Version](https://img.shields.io/badge/version-1.2.3--dev-blueviolet?style=for-the-badge)
![Kotlin](https://img.shields.io/badge/Kotlin-2.3.0-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)
![Compose](https://img.shields.io/badge/Compose-Multiplatform-4285F4?style=for-the-badge&logo=jetpackcompose&logoColor=white)
![License](https://img.shields.io/badge/License-GPLv3-green?style=for-the-badge)

<br>

**[ 🇬🇧 English ]** | [ 🇷🇺 Русский ](README_RU.md) | [ 🇩🇪 Deutsch ](README_DE.md)

</div>

> **An unofficial** launcher for the [SmartyCraft](https://smartycraft.ru) project.
> Created as a lightweight, fast, and open alternative to the official client.

**Aura Launcher** is more than just a "Play" button. It is a modern home for your game, written from scratch in **Kotlin** using **Compose Multiplatform**.

We believe that launching your favorite server shouldn't feel like interacting with a relic from 2013. Unlike the original client, Aura doesn't drag along ancient Java versions, starts instantly, and respects your operating system — whether you're on Linux, Windows, or macOS.

<p align="center">
  <img src="assets/servers.png" alt="Server Selection" width="800" style="border-radius: 10px; box-shadow: 0 4px 20px rgba(0,0,0,0.5);">
</p>

## ✨ Why Aura?

We built Aura because we love efficient software.

* **Breathe Easy:** No bloat. The interface is powered by **Compose Desktop**, making it responsive and resource-efficient.
* **Atmospheric:** The launcher lives with you. It features dynamic **seasonal themes**—from gentle winter snowfall to summer fireflies—that automatically adapt to the time of year (or your mood).
* **Linux First:** We don't treat Linux as a second-class citizen. Aura offers a native experience with AppImage support, seamless Wayland integration, and no "dancing with tambourines" required to get Java working.
* **Transparent & Open:** You deserve to know what runs on your machine. Our code is 100% open source under GPLv3.

## 🎨 Gallery

### Login & Profile
|                        Account Login                         |                      Player Profile                       |
|:------------------------------------------------------------:|:---------------------------------------------------------:|
| <img src="assets/login.png" alt="Login Screen" width="100%"> | <img src="assets/profile.png" alt="Profile" width="100%"> |

### Settings & Customization
|                              Global Settings                              |                          Client Configuration                           |
|:-------------------------------------------------------------------------:|:-----------------------------------------------------------------------:|
| <img src="assets/main_settings.png" alt="Launcher Settings" width="100%"> | <img src="assets/server_settings.png" alt="Game Settings" width="100%"> |

---

## 📚 Documentation

For developers, contributors, and the curious.
We maintain detailed technical documentation covering the architecture, seasonal engine, and network stack.

👉 **[Explore the Developer Wiki](docs/en/index.md)**

---

## 🚀 Installation

Grab the latest version from our **[Releases](https://github.com/Kitty-Hivens/Aura-Launcher/releases)** page.

### 🐧 Linux
We prioritize your experience.
* **AppImage:** The universal key. Just download, make it executable (`chmod +x AuraLauncher.AppImage`), and launch.
* **Native Packages:** `.deb` (Debian/Ubuntu/Mint) and `.rpm` (Fedora/RedHat/OpenSUSE) are also available for a seamless system integration.

### 🪟 Windows
* Download and run the `.msi` installer.
* Aura handles the Java environment for the game automatically. No manual configuration needed.

### 🍎 macOS
* Download the `.dmg` image.
* Drag and drop the application into your `Applications` folder.
* *Supports both Intel and Apple Silicon.*

## 🛠️ Building from Source

For those who like to tinker.
You will need **JDK 25** or higher.

1.  **Clone the repository:**
    ```bash
    git clone https://github.com/Kitty-Hivens/Aura-Launcher.git
    cd Aura-Launcher
    ```

2.  **Build the distribution:**
    * **Linux / macOS:**
        ```bash
        ./gradlew :client-ui:packageDistribution
        ```
    * **Windows:**
        ```bash
        gradlew.bat :client-ui:packageDistribution
        ```

3.  **Locate the artifacts:**
    Your fresh build will be waiting in:
    `client-ui/build/compose/binaries/main/`

## ⚖️ Disclaimer

This project is **unofficial software**.
The developer of Aura Launcher is not affiliated with the administration of SmartyCraft.
All rights to server content, mods, and trademarks belong to their respective legal owners.

## 📄 License

This project is distributed under the **GNU GPL v3** license.
This guarantees that Aura (and any modifications to it) will always remain free and open source.

---

<div align="center">
  <i>Made with 💜 and Kotlin by <a href="https://github.com/Kitty-Hivens">Kitty-Hivens</a></i>
</div>
