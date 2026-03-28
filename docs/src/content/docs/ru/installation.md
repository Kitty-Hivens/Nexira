---
title: Установка
description: Как установить Aura Launcher на Windows, Linux и macOS.
---

Скачай последний релиз с [GitHub Releases](https://github.com/Kitty-Hivens/Aura-Launcher/releases/latest).

## Windows

**Установщик (рекомендуется)**

1. Скачай `AuraLauncher-*-Setup.exe`
2. Запусти — права администратора не нужны
3. Лаунчер установится в `%AppData%\AuraLauncher`

**Portable**

1. Скачай `AuraLauncher-*-Windows-Portable.zip`
2. Распакуй куда угодно
3. Запусти `AuraLauncher.exe`

## Linux

1. Скачай `AuraLauncher-*-x86_64.AppImage`
2. Сделай исполняемым и запусти:

```bash
chmod +x AuraLauncher-*.AppImage
./AuraLauncher-*.AppImage
```

:::tip
В большинстве DE можно правой кнопкой → Свойства → Разрешить выполнение как программы.
:::

:::note[Нужен FUSE]
AppImage требует FUSE. На Ubuntu 22.04+ может не быть по умолчанию:

```bash
sudo apt install libfuse2
```
:::

## macOS

1. Скачай `AuraLauncher-*.dmg`
2. Открой DMG и перетащи приложение в Applications
3. При первом запуске: правой кнопкой → **Открыть**, если macOS заблокировал

:::note
**Apple Silicon** — нативно.  
**Intel** — через Rosetta 2, устанавливается автоматически.
:::

## Первый запуск

1. Войди с данными аккаунта SMARTYcraft
2. Выбери сервер
3. Нажми **Играть** — лаунчер синхронизирует файлы при первом запуске

Данные хранятся в:
- Linux/macOS: `~/.aura/`
- Windows: `%AppData%\.aura`
