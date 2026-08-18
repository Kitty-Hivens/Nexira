---
title: Widget modules
description: How to install, verify and remove widget modules in Nexira.
---

A widget module is a `.jar` file that adds widgets to the launcher. There is no installer and no registry to update: the file is either in the widgets folder or it is not.

## Where the widgets folder is

| System | Path |
| --- | --- |
| Windows | `%LOCALAPPDATA%\Nexira\widgets` |
| macOS | `~/Library/Application Support/Nexira/widgets` |
| Linux | `~/.local/share/nexira/widgets` |

On Linux the folder follows `XDG_DATA_HOME` if you have set it. If you moved the data directory (Settings, "Move data directory") or set `NEXIRA_DATA_DIR`, the widgets folder moves with it.

Create the folder if it is not there yet.

## Installing

1. Put the `.jar` into the widgets folder.
2. Restart the launcher.

Modules are read once, at startup. A jar added while the launcher is running is picked up on the next launch.

## Removing

Delete the jar and restart. Nothing else is left behind.

:::caution[Remove the widget from your layout first]
A widget whose module is gone stops being drawn. If a launcher update also changes the layout format while the module is missing, the widget is dropped from your layout for good, and reinstalling the module will not bring your arrangement back. If you plan to keep a module, keep its jar.
:::

## Checking whether a module loaded

The launcher writes what it found to the log. Each loaded module appears with its name and the widgets it brought:

```
Widget module 'pixelplayer' (Pixel Player) loaded from widget-pixelplayer.jar with 1 widget(s): pixelplayer.bar
```

A module that was refused says so, with the reason:

```
Widget module old-thing.jar was not loaded: built for widget API 1, this launcher speaks 2
```

The logs folder sits next to the widgets folder, under `logs`. Settings has a shortcut to it under Diagnostics.

## Why a module might be refused

**Built for a different launcher version.** Widgets are compiled against the launcher's interface, and a module built against a different one can fail in ways that are hard to see. The launcher refuses it outright instead. Look for a newer build of the module.

**Not a widget module.** An ordinary jar that happens to be in the folder is ignored with a note. Nothing breaks.

**Damaged file.** A truncated or corrupt jar is skipped. Other modules in the folder still load.

## What a module can do

A widget module is ordinary code with the same access to your computer as the launcher itself: your files, your network. It is not sandboxed, and the launcher does not restrict what a module may do.

Treat a widget module the way you would treat a game mod. Install ones whose source you can see, from somewhere you would go back to.

## Writing your own

See [Writing a widget module](/Nexira/dev/widgets/).
