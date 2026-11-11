---
title: Code signing policy
description: Which of Nexira's Windows files are signed, what Windows does with the ones that are not, and what the launcher sends over the network.
---

Nexira's Windows builds are not code signed with a certificate of the project yet. Windows shows the launcher as coming from an unknown publisher, and on a system with Smart App Control turned on it blocks the launcher outright. Smart App Control has no exception for a single app: the only way past it is to turn it off for the whole system.

## What is signed

- **The Java runtime inside the launcher.** Its files carry the signatures BellSoft gave them. The build takes BellSoft's signed copies rather than the unsigned ones the runtime is assembled from.
- **The Java a game runs on**, downloaded from BellSoft, signed. When BellSoft cannot be reached it comes from Adoptium instead, as Adoptium ships it.

## What is not signed

- `Nexira.exe` and the installer (`Nexira-*-Setup.exe`).
- The graphics library the interface draws with, unpacked on first start.
- The video decoding libraries (FFmpeg) that play moving wallpapers and videos, unpacked on first use.
- Files a game downloads for itself, such as the native libraries of its mod loader.

Libraries built by others keep the signature, or the lack of one, their authors gave them.

## How the builds are made

Every Windows release is built by GitHub Actions from this repository's source code, at the tag the release is cut from. The build scripts and the workflow that produce it ([`build_release.yml`](https://github.com/Kitty-Hivens/Nexira/blob/stable/.github/workflows/build_release.yml)) are part of that source. Nothing built on a developer's machine is published.

Nightly builds are cut from the development branch every night and are meant for testers.

Nexira is maintained by one person. A change proposed by anyone else arrives as a pull request and is reviewed before it is merged.

## Privacy

Nexira collects no telemetry, sends no analytics and uploads no crash reports. Crash reports and logs stay on your computer until you choose to share them.

The launcher connects to other systems only to do what it is used for:

- **GitHub**, to check for launcher updates and read their release notes, to find releases of the Cleanroom and lwjgl3ify loaders, and on Android to download the Java and graphics libraries made for the phone.
- **The services of the content you choose to play**: Mojang and Microsoft for the game, your SMARTYcraft account when you sign in, SMARTYcraft and its mirror for SMARTYcraft packs, Modrinth when you browse or install from it, and the repositories of mod loaders a pack uses.
- **BellSoft**, to download the Java runtime a game needs.
- **The news sources** shown on the home screen.
- **YouTube's image server**, for the preview picture of a video a description embeds.
- **The video sites you link to**, when you play a video or set one as a background by its address: the launcher fetches it with yt-dlp, which it downloads from GitHub the first time.

Each of those services has its own privacy policy, which applies to what you do there.
