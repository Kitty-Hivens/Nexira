# Branding sources

This directory holds the canonical icon sources for the launcher. Every
PNG/ICO under `client-ui/.../drawable/` and `resources/icons/` is derived
from one of the files here.

## Files

| Source           | Role                                                              |
|------------------|-------------------------------------------------------------------|
| `app-icon.png`   | Launcher identity — appears on the user's desktop, Start menu,    |
|                  | Applications folder, AppImage hicolor icon, Inno Setup installer  |
| `tray-icon.png`  | In-app icon — system tray and main window chrome at runtime;      |
|                  | should remain readable when scaled to 16-32 px                    |

The two are intentionally separate because the design constraints differ
— a tray glyph at 16 px shouldn't carry the detail of a 512 px launcher
icon. Replacing both with a single source is acceptable when the design
is geometric enough to read at every size.

## Regenerate every variant

```
./scripts/regenerate-icons.sh
```

Requires ImageMagick (`magick` or legacy `convert`) in `$PATH`. Outputs
land in their build-system-mandated locations and should be committed
alongside the source change so contributors without ImageMagick can
still build the project.

## Generated files (do not edit by hand)

| Path                                                             | Format                               | Purpose                                              |
|------------------------------------------------------------------|--------------------------------------|------------------------------------------------------|
| `client-ui/src/commonMain/composeResources/drawable/icon.png`    | PNG 1024×1024                        | `Res.drawable.icon` — hi-res window chrome (Main.kt) |
| `client-ui/src/commonMain/composeResources/drawable/favicon.png` | PNG 64×64                            | `Res.drawable.favicon` — tray + AboutScreen          |
| `resources/icons/icon.ico`                                       | Multi-size ICO (16/32/48/64/128/256) | Compose Windows iconFile + Inno Setup                |
| `resources/icons/256x256.png`                                    | PNG 256×256                          | AppImage hicolor 256                                 |
| `resources/icons/512x512.png`                                    | PNG 512×512                          | AppImage hicolor 512                                 |

**Why is `icon.ico` not under `composeResources/drawable/`?** Compose Resources
groups files by stem-without-extension. Putting `icon.ico` next to `icon.png`
makes `Res.drawable.icon` resolve to *both* files and crashes
`painterResource` at runtime. Installer assets live under `resources/icons/`
where Compose doesn't index them.

## Source preference

Prefer SVG when redesigning — vector survives infinite resizing and
keeps the ICO crisp at every Windows size. The current `*.png` sources
are temporary; swap to `app-icon.svg` / `tray-icon.svg` as soon as a
designer hands one over and update `regenerate-icons.sh` to rasterize
through Inkscape (`inkscape -w … -h … -o …`).
