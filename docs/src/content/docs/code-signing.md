---
title: Code signing policy
description: How Nexira's Windows builds are signed, who approves a signature, and what the launcher sends over the network.
---

Free code signing provided by [SignPath.io](https://about.signpath.io), certificate by [SignPath Foundation](https://signpath.org).

## What is signed

The Windows installer (`Nexira-*-Setup.exe`) and the launcher executable inside both the installer and the portable archive, for every stable and beta release published on [GitHub Releases](https://github.com/Kitty-Hivens/Nexira/releases).

Every signed file is built by GitHub Actions from this repository's source code, at the tag the release is cut from. The build scripts and the workflow that produce it ([`build_release.yml`](https://github.com/Kitty-Hivens/Nexira/blob/stable/.github/workflows/build_release.yml)) are part of that source. Nothing built on a developer's machine is signed.

Nightly builds are not signed. They are cut from the development branch every night and are meant for testers.

Libraries Nexira ships that were built by others keep the signature, or the lack of one, their authors gave them.

## Team roles

Nexira is maintained by one person.

| Role                     | Members                                                     |
|--------------------------|-------------------------------------------------------------|
| Committers and reviewers | [Kitty-Hivens maintainers](https://github.com/Kitty-Hivens) |
| Approvers                | [Kitty-Hivens maintainers](https://github.com/Kitty-Hivens) |

A change proposed by anyone else arrives as a pull request and is reviewed before it is merged. Every signing request is approved by hand.

## Privacy

Nexira collects no telemetry, sends no analytics and uploads no crash reports. Crash reports and logs stay on your computer until you choose to share them.

The launcher connects to other systems only to do what it is used for:

- **GitHub**, to check for launcher updates and read their release notes.
- **The services of the content you choose to play**: Mojang and Microsoft for the game, your Microsoft or SMARTYcraft account when you sign in, SMARTYcraft and its mirror for SMARTYcraft packs, Modrinth when you browse or install from it, and the repositories of mod loaders a pack uses.
- **BellSoft**, to download the Java runtime a game needs.
- **The news sources** shown on the home screen.

Each of those services has its own privacy policy, which applies to what you do there.
