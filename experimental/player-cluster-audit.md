# The player cluster, checked against its issues

Verified 2026-08-18 against the working tree at `8f358beb`. The unit is the component
plus everything it connects to, not a file: three player surfaces, the engine behind
them, the cross-widget service, and the six open issues filed against them.

The reason to record this: four of the six issues describe a state the code has left.
Reading the tracker alone would have produced work that is already done.

## What was read

| file | lines |
|---|---|
| `widgets/sample/MusicPlayerWidget.kt` | 464 |
| `widgets/sample/PlaybackMiniControlWidget.kt` | 314 |
| `widgets/sample/VideoPlayerWidget.kt` | 121 |
| `widgets/sample/PlaybackMeasure.kt` | 41 |
| `widgets/services/MusicPlayerService.kt` + `Impl` | 64 |
| `audio/AudioPlayer.kt` | 287 |
| `components/VideoMedia.kt` | 257 |
| `components/VideoPlayer.kt` | 539 |
| `components/MediaUrl.kt` | 28 |

Plus the bundled `widget-model/.../default-layout.json` and every call site of
`FullscreenVideo` / `VideoMedia`.

## Verdicts

| issue | verdict |
|---|---|
| #460 both playback widgets look wrong on sight | **resolved, all four points** |
| #458 playback state has no metadata or artwork | **resolved** |
| #455 the video player has no interaction model | **four of six resolved**, two remnants |
| #456 playback widgets are samples nothing places | **half**: the shared source exists, the placement does not |
| #457 letterbox bars render black / no now-playing composition | **both halves open** |
| #483 the music player cannot open a file | **unverified** |

### #460 -- resolved

Every complaint in the body is answered in the code, and the comments name the symptom
the issue named:

- the mini control has its own measure now, `NxProgressBar` under the transport row,
  with a comment recording that the volume bar idling at full "read as a track played to
  the end";
- the diagonal `Brush.linearGradient` on the music card is gone; the card is
  `NxSurface(NxSurfaceLevel.Floating)`;
- Material's `LinearProgressIndicator` and its stop-indicator dot are gone, replaced by
  the library primitive whose corner follows the style axis;
- both surfaces are on the surface system, and the no-provider placeholder is on
  `NxSurfaceLevel.Sunken` so an inert slot reads as one.

### #458 -- resolved

`TrackInfo` carries title, artist, album and artwork. `AudioPlayer.readMetadata` reads
`player.tags` and `player.coverArt` once per open, past the `Opening` state because
skinema fills both on its own decode thread, and decodes the picture on
`Dispatchers.Default` so a large cover does not sit in front of queued transport
commands. Both surfaces render all four fields, with the file name as the fallback.

### #455 -- four of six resolved

Resolved:

- **playback survives the swap** -- `VideoHandoff` carries position, volume, mute and
  play state; the new player seeks before the first audible frame;
- **the transport hides** -- `controlsShown = showControls && (!isPlaying || (hovered
  && !pointerIdle))`, with an idle timer;
- **clicking the picture toggles playback**, with indication suppressed because a ripple
  over a moving picture is noise;
- **the way back sits next to the way in** -- `onExitFullscreen` renders the same
  control inverted; the corner cross stays for "done watching".

Open:

- **the handoff reaches only the widget path.** `VideoPlayerWidget` passes one;
  `ImageGallery.kt:191`, `CatalogueHero.kt:160`, `PackDetailScreen.kt:522` and
  `CataloguePackDetailScreen.kt:303` all call `FullscreenVideo` without one, so a banner
  playing inline still restarts when it is opened full. The mechanism exists; four call
  sites do not use it.
- **no inline path from the gallery.** A clicked video thumbnail still goes straight to
  `FullscreenVideo`.
- the `Popup`-versus-OS-fullscreen question is still undecided, which the issue asked for
  as a decision rather than an inheritance.

### #456 -- half

The shared playback source now exists: `MusicPlayerServiceImpl` wraps the `AudioPlayer`
Koin singleton, the mini control reads it through `useService`, and removing the provider
widget leaves the engine alive so re-adding it re-binds to the same track. The issue's
"they do not share a playback source" no longer holds.

The placement half stands. `default-layout.json` names none of `home.new.music`,
`home.new.playback.mini`, `home.new.video`, and no preset does either, so all three are
unreachable on a fresh install and all three still live in `widgets/sample`.

### #457 -- open

Two black fields remain: `VideoPlayer.kt:319` (the fullscreen scrim) and
`VideoPlayerWidget.kt:71` (the widget's own frame). The letterbox fill is untouched. The
now-playing composition is a design decision plus widget kinds that do not exist, as the
issue already separated.

## What this says about method

The cluster was chosen by eye ("the gap is wrong, the fill is wrong") and the eye was
right, but not about what. The visible defects had already been fixed; what remains is
placement, four call sites that do not pass an object that exists, and one fill.

Two consequences worth carrying:

1. **Check the issue against the code before planning the work.** Four of six here
   describe a past state. A mechanical token sweep would have walked through these two
   files, changed nothing, and reported success.
2. **A resolved issue that stays open is a cost.** It was counted as scope twice in one
   day: once when picking a target, once when sizing it.
